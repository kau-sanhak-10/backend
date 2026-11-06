package com.ilro.backend.domain.inquiry;

import com.ilro.backend.domain.ai.service.AiAnalysisRequested;
import com.ilro.backend.domain.ai.service.AiAnalysisStore;
import com.ilro.backend.support.TestAccounts;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 사장님 홈·문의 목록·상세·수정 (BE-07)과 응대 전환·사람 응대 요청 (BE-08), 명세 4.2·4.5·5.5
@SpringBootTest
@RecordApplicationEvents
class OwnerInquiryFlowTest {

    private static final String ALL_CONSENTS = """
            {"consents":[{"type":"PRIVACY_REQUIRED","termsVersion":"2026-10-v1"},
                         {"type":"PHOTO","termsVersion":"2026-10-v1"}]}""";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ApplicationEvents events;
    @Autowired
    private AiAnalysisStore aiStore;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private long ownerId;
    private String ownerToken;

    private record Session(long inquiryId, String token) {

        String path(String suffix) {
            return "/api/v1/customer/inquiries/" + inquiryId + suffix;
        }

        String ownerPath(String suffix) {
            return "/api/v1/owner/inquiries/" + inquiryId + suffix;
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        accounts = new TestAccounts(jdbc);
        ownerId = accounts.approvedOwner(accounts.newPhone());
        ownerToken = accounts.ownerToken(ownerId);
        jdbc.update("INSERT INTO owner_work_type (owner_id, work_category, duration_minutes, is_active) "
                + "VALUES (?, 'REPAIR', 60, true), (?, 'LEAK', 90, false)", ownerId, ownerId);
    }

    @AfterEach
    void tearDown() {
        accounts.cleanUp();
    }

    // ---------- BE-07 사장님 홈·문의 ----------

    @Test
    @DisplayName("홈 - 확인 필요 문의·대기 중 요청·앱 안 미확인 알림 수, 오늘(한국 날짜) 취소되지 않은 방문만")
    void home() throws Exception {
        Session needsCheck = startInquiry();
        Session deleted = startInquiry();
        Session requested = startInquiry();
        jdbc.update("UPDATE inquiry SET status = 'OWNER_CHECK' WHERE id IN (?, ?)", needsCheck.inquiryId(),
                deleted.inquiryId());
        jdbc.update("UPDATE inquiry SET deleted_at = now() WHERE id = ?", deleted.inquiryId());
        // 직접 상담 요청 → 사장님 확인 + 대기 요청 + 알림 1
        postAs(requested.token(), requested.path("/handoff-requests"), "").andExpect(status().isCreated());
        jdbc.update("INSERT INTO notification (owner_id, recipient, event_type, ref_type, ref_id, channel, "
                + "dedupe_key, read_at) VALUES (?, 'OWNER', 'NEEDS_CHECK', 'INQUIRY', ?, 'IN_APP', ?, now()), "
                + "(?, 'OWNER', 'NEEDS_CHECK', 'INQUIRY', ?, 'WEB_PUSH', ?, null)",
                ownerId, needsCheck.inquiryId(), "read-" + UUID.randomUUID(),
                ownerId, needsCheck.inquiryId(), "push-" + UUID.randomUUID());
        LocalDate today = LocalDate.now(KST);
        long visit = reservation(needsCheck.inquiryId(), "CONFIRMED", at(today, 10));
        // 한국 날짜 경계 - 오늘 00:30은 포함(UTC로는 어제), 어제 23:00은 제외
        long earlyVisit = reservation(null, "CONFIRMED", today.atTime(0, 30).atOffset(ZoneOffset.ofHours(9)));
        reservation(null, "CONFIRMED", at(today.minusDays(1), 23));
        reservation(null, "CANCELED", at(today, 13));
        reservation(null, "CONFIRMED", at(today.plusDays(1), 10));
        long otherOwner = accounts.approvedOwner(accounts.newPhone());
        jdbc.update("INSERT INTO reservation (owner_id, start_at, end_at, status) VALUES (?, ?, ?, 'CONFIRMED')",
                otherOwner, at(today, 9), at(today, 10));

        getAs(ownerToken, "/api/v1/owner/home")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.today").value(today.toString()))
                .andExpect(jsonPath("$.result.needsCheckCount").value(2))
                .andExpect(jsonPath("$.result.waitingHandoffCount").value(1))
                .andExpect(jsonPath("$.result.unreadNotificationCount").value(1))
                .andExpect(jsonPath("$.result.todayVisits", hasSize(2)))
                .andExpect(jsonPath("$.result.todayVisits[0].reservationId").value(earlyVisit))
                .andExpect(jsonPath("$.result.todayVisits[1].reservationId").value(visit))
                .andExpect(jsonPath("$.result.todayVisits[1].inquiryId").value(needsCheck.inquiryId()))
                .andExpect(jsonPath("$.result.todayVisits[1].startAt").value(today + "T10:00:00+09:00"));

        getAs(requested.token(), "/api/v1/owner/home").andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("목록 - 자기 업체·삭제 안 된 문의만 최근 것부터, 상태·응대 주체로 거르고 쪽 나눔")
    void list() throws Exception {
        Session ai = startInquiry();
        String longText = "보일러 아래쪽 배관에서 물이 조금씩 새고 있어요. 어제 저녁부터 그랬고 오늘 아침에는 바닥이 젖어 있었어요.";
        send(ai, longText);
        putAs(ai.token(), ai.path("/visit-info"), visitInfo(version(ai), "김고객", "010-2222-3333"))
                .andExpect(status().isOk());
        Session owner = startInquiry();
        postAs(ownerToken, owner.ownerPath("/takeover"), "").andExpect(status().isOk());
        postAs(owner.token(), owner.path("/handoff-requests"), "").andExpect(status().isCreated());
        Session deleted = startInquiry();
        jdbc.update("UPDATE inquiry SET deleted_at = now() WHERE id = ?", deleted.inquiryId());
        long otherOwner = accounts.approvedOwner(accounts.newPhone());
        startInquiry(otherOwner);

        getAs(ownerToken, "/api/v1/owner/inquiries")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.page").value(0))
                .andExpect(jsonPath("$.result.size").value(20))
                .andExpect(jsonPath("$.result.totalElements").value(2))
                .andExpect(jsonPath("$.result.totalPages").value(1))
                .andExpect(jsonPath("$.result.isLast").value(true))
                .andExpect(jsonPath("$.result.content[0].inquiryId").value(owner.inquiryId()))
                .andExpect(jsonPath("$.result.content[0].handlerMode").value("OWNER"))
                .andExpect(jsonPath("$.result.content[0].handoff.requestType").value("DIRECT_CHAT"))
                .andExpect(jsonPath("$.result.content[0].handoff.status").value("WAITING"))
                .andExpect(jsonPath("$.result.content[0].lastMessage").value(nullValue()))
                .andExpect(jsonPath("$.result.content[1].inquiryId").value(ai.inquiryId()))
                .andExpect(jsonPath("$.result.content[1].customerName").value("김고객"))
                .andExpect(jsonPath("$.result.content[1].customerPhone").value("01022223333"))
                .andExpect(jsonPath("$.result.content[1].workCategory").value("REPAIR"))
                .andExpect(jsonPath("$.result.content[1].handoff").value(nullValue()))
                .andExpect(jsonPath("$.result.content[1].lastMessage.sender").value("CUSTOMER"))
                .andExpect(jsonPath("$.result.content[1].lastMessage.preview").value(longText.substring(0, 50)));

        getAs(ownerToken, "/api/v1/owner/inquiries?handlerMode=OWNER")
                .andExpect(jsonPath("$.result.content[*].inquiryId", contains((int) owner.inquiryId())));
        getAs(ownerToken, "/api/v1/owner/inquiries?status=COLLECTING&handlerMode=AI")
                .andExpect(jsonPath("$.result.content[*].inquiryId", contains((int) ai.inquiryId())));
        getAs(ownerToken, "/api/v1/owner/inquiries?size=1")
                .andExpect(jsonPath("$.result.content[*].inquiryId", contains((int) owner.inquiryId())))
                .andExpect(jsonPath("$.result.totalPages").value(2))
                .andExpect(jsonPath("$.result.isLast").value(false));
        getAs(ownerToken, "/api/v1/owner/inquiries?size=1&page=1")
                .andExpect(jsonPath("$.result.content[*].inquiryId", contains((int) ai.inquiryId())))
                .andExpect(jsonPath("$.result.isLast").value(true));

        getAs(ownerToken, "/api/v1/owner/inquiries?size=100").andExpect(status().isOk());
        getAs(ownerToken, "/api/v1/owner/inquiries?size=101&page=-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[*].field", contains("page", "size")));
        getAs(ownerToken, "/api/v1/owner/inquiries?page=" + Integer.MAX_VALUE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("page"));

        // 미리보기는 글자 단위로 자름 - 이모지가 반으로 잘리지 않음
        send(ai, "😀".repeat(60));
        getAs(ownerToken, "/api/v1/owner/inquiries")
                .andExpect(jsonPath("$.result.content[1].lastMessage.preview").value("😀".repeat(50)));
        getAs(ownerToken, "/api/v1/owner/inquiries?status=WAITING").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("상세 접근 - 다른 업체·삭제된 문의는 404, 고객 토큰은 403")
    void detailAccess() throws Exception {
        Session session = startInquiry();
        String otherToken = accounts.ownerToken(accounts.approvedOwner(accounts.newPhone()));
        getAs(otherToken, session.ownerPath("")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
        getAs(otherToken, session.ownerPath("/messages")).andExpect(status().isNotFound());
        patchAs(otherToken, session.ownerPath(""), ownerFields(0, field("symptom", "누수")))
                .andExpect(status().isNotFound());
        postAs(otherToken, session.ownerPath("/takeover"), "").andExpect(status().isNotFound());
        postAs(otherToken, session.ownerPath("/messages"), ownerMessage("x-1", "안녕하세요"))
                .andExpect(status().isNotFound());
        postAs(otherToken, session.ownerPath("/resume-ai"), "").andExpect(status().isNotFound());
        postAs(otherToken, session.ownerPath("/close"), "").andExpect(status().isNotFound());
        getAs(session.token(), session.ownerPath("")).andExpect(status().isForbidden());
        Session another = startInquiry();
        postAs(another.token(), session.path("/handoff-requests"), "").andExpect(status().isNotFound());
        postAs(another.token(), session.path("/callback-requests"), callback("김고객", "010-2222-3333"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM handoff_request WHERE inquiry_id = ?", Integer.class,
                session.inquiryId())).isZero();

        jdbc.update("UPDATE inquiry SET deleted_at = now() WHERE id = ?", session.inquiryId());
        getAs(ownerToken, session.ownerPath("")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
    }

    @Test
    @DisplayName("상세 - 항목별 값·출처·필수 여부·미확인 이유·이력, AI 해석, 사진, 회신 요청, 연결 예약, 대화")
    void detail() throws Exception {
        Session session = startInquiry();
        long messageId = send(session, "물이 새요");
        long photoId = donePhoto(session);
        postAs(session.token(), session.path("/messages"),
                "{\"clientMsgId\":\"" + UUID.randomUUID() + "\",\"photoIds\":[" + photoId + "]}")
                .andExpect(status().isCreated());
        putAs(session.token(), session.path("/visit-info"), visitInfo(version(session), "김고객", "010-2222-3333"))
                .andExpect(status().isOk());
        patchAs(session.token(), session.path("/fields"), customerFields(version(session),
                field("symptom", "물이 새요"), "{\"fieldKey\":\"model\",\"unsure\":true}")).andExpect(status().isOk());
        patchAs(session.token(), session.path("/fields"),
                customerFields(version(session), field("symptom", "온수가 안 나와요"))).andExpect(status().isOk());
        jdbc.update("INSERT INTO ai_decision (inquiry_id, message_id, stage, action, decided_by, status, "
                        + "output_raw, input_version, request_id) VALUES (?, ?, 'DECIDE', 'ASK_MORE_INFO', 'EXTERNAL_API', "
                        + "'SUCCESS', ?::jsonb, 1, ?)", session.inquiryId(), messageId, """
                        {"success":true,"result":{"interpretations":[{"text":"배관 누수로 보여요","basis":"고객 설명"}],
                         "conflicts":[],"safety":{"flag":false,"reasons":[]},
                         "nextAction":{"action":"ASK_MORE_INFO","reason":"모델명 확인 필요"}}}""",
                UUID.randomUUID().toString());
        postAs(session.token(), session.path("/callback-requests"), callback("김고객", "010-9876-5432"))
                .andExpect(status().isCreated());
        long reservationId = reservation(session.inquiryId(), "REQUESTED", at(LocalDate.now(KST).plusDays(2), 10));

        getAs(ownerToken, session.ownerPath(""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("OWNER_CHECK"))
                .andExpect(jsonPath("$.result.handlerMode").value("AI"))
                .andExpect(jsonPath("$.result.safetyFlag").value(false))
                .andExpect(jsonPath("$.result.version").value(version(session)))
                .andExpect(jsonPath("$.result.visitInfo.name").value("김고객"))
                .andExpect(jsonPath("$.result.visitInfo.phone").value("01022223333"))
                .andExpect(jsonPath("$.result.phoneVerified").value(false))
                .andExpect(jsonPath("$.result.fields[*].fieldKey",
                        contains("manufacturer", "model", "boiler_type", "error_code", "symptom")))
                .andExpect(jsonPath("$.result.fields[0].value").value(nullValue()))
                .andExpect(jsonPath("$.result.fields[0].source").value(nullValue()))
                .andExpect(jsonPath("$.result.fields[0].status").value("UNCONFIRMED"))
                .andExpect(jsonPath("$.result.fields[0].unconfirmedReason").value("NOT_PROVIDED"))
                .andExpect(jsonPath("$.result.fields[0].required").value(false))
                .andExpect(jsonPath("$.result.fields[1].status").value("UNCONFIRMED"))
                .andExpect(jsonPath("$.result.fields[1].unconfirmedReason").value("CUSTOMER_UNSURE"))
                .andExpect(jsonPath("$.result.fields[4].required").value(true))
                .andExpect(jsonPath("$.result.fields[4].value").value("온수가 안 나와요"))
                .andExpect(jsonPath("$.result.fields[4].source").value("CUSTOMER"))
                .andExpect(jsonPath("$.result.fields[4].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.result.fields[4].history[*].value", contains("물이 새요")))
                .andExpect(jsonPath("$.result.aiSummary.summary").value(nullValue()))
                .andExpect(jsonPath("$.result.aiSummary.interpretations[0].text").value("배관 누수로 보여요"))
                .andExpect(jsonPath("$.result.aiSummary.conflicts", hasSize(0)))
                .andExpect(jsonPath("$.result.aiSummary.nextAction").value("ASK_MORE_INFO"))
                .andExpect(jsonPath("$.result.aiSummary.nextActionReason").value("모델명 확인 필요"))
                .andExpect(jsonPath("$.result.aiSummary.decidedAt").value(notNullValue()))
                .andExpect(jsonPath("$.result.photos[0].photoId").value(photoId))
                .andExpect(jsonPath("$.result.photos[0].messageId").value(notNullValue()))
                .andExpect(jsonPath("$.result.handoffRequests[0].requestType").value("CALLBACK"))
                .andExpect(jsonPath("$.result.handoffRequests[0].name").value("김고객"))
                .andExpect(jsonPath("$.result.handoffRequests[0].phone").value("01098765432"))
                .andExpect(jsonPath("$.result.handoffRequests[0].status").value("WAITING"))
                .andExpect(jsonPath("$.result.reservation.reservationId").value(reservationId))
                .andExpect(jsonPath("$.result.consents", contains("PRIVACY_REQUIRED", "PHOTO")));

        getAs(ownerToken, session.ownerPath("/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.messages[*].sender", contains("CUSTOMER", "CUSTOMER")))
                .andExpect(jsonPath("$.result.messages[1].photoIds[0]").value(photoId));
    }

    @Test
    @DisplayName("사장님 항목 수정 - 출처 OWNER·확인됨, 이전 값은 이력, 바뀌면 version·AI 입력 순번 1씩, 같은 값이면 그대로")
    void ownerFields() throws Exception {
        Session session = startInquiry();
        patchAs(session.token(), session.path("/fields"),
                customerFields(version(session), field("symptom", "물이 새요"))).andExpect(status().isOk());
        int before = version(session);
        long seqBefore = aiInputSeq(session);

        patchAs(ownerToken, session.ownerPath(""),
                ownerFields(before, field("symptom", " 배관 누수 "), field("model", "NCB-500")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value(before + 1))
                .andExpect(jsonPath("$.result.fields[1].value").value("NCB-500"))
                .andExpect(jsonPath("$.result.fields[1].source").value("OWNER"))
                .andExpect(jsonPath("$.result.fields[4].value").value("배관 누수"))
                .andExpect(jsonPath("$.result.fields[4].source").value("OWNER"))
                .andExpect(jsonPath("$.result.fields[4].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.result.fields[4].history[*].value", contains("물이 새요")));
        assertThat(aiInputSeq(session)).isEqualTo(seqBefore + 1);
        assertThat(jdbc.queryForMap("SELECT symptom, model FROM inquiry WHERE id = ?", session.inquiryId()))
                .containsEntry("symptom", "배관 누수").containsEntry("model", "NCB-500");

        // 같은 값 다시 - 새 행·version·순번 그대로
        patchAs(ownerToken, session.ownerPath(""), ownerFields(before + 1, field("symptom", "배관 누수")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value(before + 1));
        assertThat(aiInputSeq(session)).isEqualTo(seqBefore + 1);
        assertThat(fieldRows(session, "symptom")).isEqualTo(2);

        // 고객이 다른 값으로 고치면 저장하되 미확인(CONFLICT) - 사장님이 다시 확인
        patchAs(session.token(), session.path("/fields"),
                customerFields(before + 1, field("symptom", "온수가 안 나와요"))).andExpect(status().isOk());
        getAs(ownerToken, session.ownerPath(""))
                .andExpect(jsonPath("$.result.fields[4].value").value("온수가 안 나와요"))
                .andExpect(jsonPath("$.result.fields[4].status").value("UNCONFIRMED"))
                .andExpect(jsonPath("$.result.fields[4].unconfirmedReason").value("CONFLICT"));

        // 고객이 같은 값을 다시 보내거나 '모름'으로 보내도 그대로 - 충돌이 풀리지 않음
        int conflictVersion = version(session);
        patchAs(session.token(), session.path("/fields"),
                customerFields(conflictVersion, field("symptom", "온수가 안 나와요"))).andExpect(status().isOk());
        patchAs(session.token(), session.path("/fields"), "{\"version\":" + conflictVersion
                + ",\"fields\":[{\"fieldKey\":\"symptom\",\"unsure\":true}]}").andExpect(status().isOk());
        assertThat(version(session)).isEqualTo(conflictVersion);
        // 다른 값으로 또 고쳐도 미확인 그대로, AI 재개도 막힘
        patchAs(session.token(), session.path("/fields"),
                customerFields(conflictVersion, field("symptom", "보일러가 안 켜져요"))).andExpect(status().isOk());
        getAs(ownerToken, session.ownerPath(""))
                .andExpect(jsonPath("$.result.fields[4].value").value("보일러가 안 켜져요"))
                .andExpect(jsonPath("$.result.fields[4].status").value("UNCONFIRMED"))
                .andExpect(jsonPath("$.result.fields[4].unconfirmedReason").value("CONFLICT"));
        postAs(ownerToken, session.ownerPath("/resume-ai"), "")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.result.reasons", contains("REQUIRED_FIELD")));

        patchAs(ownerToken, session.ownerPath(""), ownerFields(before, field("symptom", "누수")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INQUIRY_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.result.currentVersion").value(version(session)));
        patchAs(ownerToken, session.ownerPath(""), ownerFields(version(session), field("address", "서울"),
                field("model", "x".repeat(101)), field("symptom", "a"), field("symptom", "b")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[*].field",
                        contains("fields[0].fieldKey", "fields[1].value", "fields[3].fieldKey")));
        patchAs(ownerToken, session.ownerPath(""), "{\"version\":" + version(session) + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("visitInfo"));

        // 사장님이 정하면 풀리고 AI를 다시 켤 수 있음
        patchAs(ownerToken, session.ownerPath(""), ownerFields(version(session), field("symptom", "온수 안 나옴")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.fields[4].source").value("OWNER"))
                .andExpect(jsonPath("$.result.fields[4].status").value("CONFIRMED"));
        postAs(ownerToken, session.ownerPath("/resume-ai"), "").andExpect(status().isOk());
    }

    @Test
    @DisplayName("필수 항목 충돌 - 고객이 사장님 값과 다르게 고치면 사장님 확인+알림 한 번, AI는 멈춤, 참고 항목은 그대로")
    void requiredFieldConflict() throws Exception {
        Session session = startInquiry();
        patchAs(ownerToken, session.ownerPath(""),
                ownerFields(version(session), field("symptom", "누수"), field("model", "NCB-500")))
                .andExpect(status().isOk());

        patchAs(session.token(), session.path("/fields"), customerFields(version(session), field("model", "NCB-300")))
                .andExpect(status().isOk());
        assertThat(inquiry(session)).containsEntry("status", "COLLECTING");
        assertThat(notifications(session)).isZero();

        int before = version(session);
        patchAs(session.token(), session.path("/fields"), customerFields(before, field("symptom", "온수 안 나옴")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value(before + 1));
        assertThat(inquiry(session)).containsEntry("status", "OWNER_CHECK");
        assertThat(notifications(session)).isEqualTo(1);
        postAs(session.token(), session.path("/messages"), customerMessage("c-2", "언제 오시나요"))
                .andExpect(jsonPath("$.result.aiStatus").value("NONE"));

        patchAs(session.token(), session.path("/fields"), customerFields(version(session), field("symptom", "안 켜져요")))
                .andExpect(status().isOk());
        assertThat(notifications(session)).isEqualTo(1);
    }

    @Test
    @DisplayName("AI가 찾은 충돌은 사장님 판단 대기가 아님 - 고객이 답하면 풀림")
    void aiConflictCustomerAnswers() throws Exception {
        Session session = startInquiry();
        long messageId = send(session, "귀뚜라미 같기도 하고 나비엔 같기도 해요");
        long decisionId = jdbc.queryForObject("INSERT INTO ai_decision (inquiry_id, message_id, stage, action, "
                + "decided_by, status, input_version, request_id) VALUES (?, ?, 'DECIDE', 'ASK_MORE_INFO', "
                + "'EXTERNAL_API', 'SUCCESS', 1, ?) RETURNING id", Long.class, session.inquiryId(), messageId,
                UUID.randomUUID().toString());
        jdbc.update("INSERT INTO inquiry_field (inquiry_id, field_key, source, status, unconfirmed_reason, "
                + "ai_decision_id, message_id) VALUES (?, 'manufacturer', 'CUSTOMER', 'UNCONFIRMED', 'CONFLICT', ?, ?)",
                session.inquiryId(), decisionId, messageId);

        patchAs(session.token(), session.path("/fields"),
                customerFields(version(session), field("manufacturer", "경동나비엔")))
                .andExpect(status().isOk());

        getAs(ownerToken, session.ownerPath(""))
                .andExpect(jsonPath("$.result.fields[0].value").value("경동나비엔"))
                .andExpect(jsonPath("$.result.fields[0].status").value("CONFIRMED"));
        assertThat(inquiry(session)).containsEntry("status", "COLLECTING");
    }

    @Test
    @DisplayName("사장님 방문정보 수정 - 이름은 그대로 저장, 진행 중 예약의 주소·연락처는 예약 변경으로, 종료된 문의는 409")
    void ownerVisitInfo() throws Exception {
        Session session = startInquiry();
        putAs(session.token(), session.path("/visit-info"), visitInfo(version(session), "김고객", "010-2222-3333"))
                .andExpect(status().isOk());
        // 같은 번호를 쓰는 다른 문의가 있어도 사장님이 고친 이름은 저장
        Session other = startInquiry();
        putAs(other.token(), other.path("/visit-info"), visitInfo(version(other), "다른이름", "010-2222-3333"))
                .andExpect(status().isOk());

        int before = version(session);
        long seqBefore = aiInputSeq(session);
        patchAs(ownerToken, session.ownerPath(""), ownerVisit(before, "김민수", "010-2222-3333",
                "경기 고양시 덕양구 화정로 1", "REPAIR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value(before + 1))
                .andExpect(jsonPath("$.result.visitInfo.name").value("김민수"))
                .andExpect(jsonPath("$.result.visitInfo.workCategory").value("REPAIR"));
        assertThat(aiInputSeq(session)).isEqualTo(seqBefore + 1);

        // 바뀐 게 없으면 version·순번 그대로 - 처리 중이던 AI 결과를 버리지 않음
        patchAs(ownerToken, session.ownerPath(""), ownerVisit(before + 1, "김민수", "01022223333",
                "경기 고양시 덕양구 화정로 1", "REPAIR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value(before + 1));
        assertThat(aiInputSeq(session)).isEqualTo(seqBefore + 1);
        int customerBefore = version(other);
        putAs(other.token(), other.path("/visit-info"), visitInfo(customerBefore, "다른이름", "010-2222-3333"))
                .andExpect(status().isOk());
        assertThat(version(other)).isEqualTo(customerBefore);

        // 방문정보·항목을 함께 고쳐도 version·순번은 1씩
        String both = ownerVisit(before + 1, "민수", "010-2222-3333", "경기 고양시 덕양구 화정로 1", "REPAIR")
                .replaceFirst("\\}\\s*$", ",\"fields\":[" + field("symptom", "누수") + "]}");
        patchAs(ownerToken, session.ownerPath(""), both)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value(before + 2))
                .andExpect(jsonPath("$.result.visitInfo.name").value("민수"))
                .andExpect(jsonPath("$.result.fields[4].value").value("누수"));
        assertThat(aiInputSeq(session)).isEqualTo(seqBefore + 2);

        patchAs(ownerToken, session.ownerPath(""), ownerVisit(version(session), "김민수", "010-12",
                "경기 고양시 덕양구 화정로 1", "LEAK"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("visitInfo.phone"));
        patchAs(ownerToken, session.ownerPath(""), ownerVisit(version(session), "김민수", "010-2222-3333",
                "경기 고양시 덕양구 화정로 1", "LEAK"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("visitInfo.workCategory"));

        long reservationId = reservation(session.inquiryId(), "CONFIRMED", at(LocalDate.now(KST).plusDays(3), 10));
        patchAs(ownerToken, session.ownerPath(""), ownerVisit(version(session), "김민수", "010-2222-3333",
                "서울 마포구 월드컵로 1", null))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RESERVATION_CHANGE_REQUIRED"))
                .andExpect(jsonPath("$.result.reservationId").value(reservationId));
        patchAs(ownerToken, session.ownerPath(""), ownerVisit(version(session), "김민수님", "010-2222-3333",
                "경기 고양시 덕양구 화정로 1", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.visitInfo.name").value("김민수님"));

        postAs(ownerToken, session.ownerPath("/close"), "").andExpect(status().isOk());
        patchAs(ownerToken, session.ownerPath(""), ownerFields(version(session), field("symptom", "누수")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INQUIRY_CLOSED"));
    }

    // ---------- BE-08 응대 전환·사람 응대 요청 ----------

    @Test
    @DisplayName("직접 응대 - 시작 전 답변은 409, 시작하면 AI 멈춤·version·AI 입력 순번 1씩, 사장님 답변은 고객이 받음")
    void takeOverAndReply() throws Exception {
        Session session = startInquiry();
        send(session, "물이 새요");
        postAs(ownerToken, session.ownerPath("/messages"), ownerMessage("m-1", "확인해 볼게요"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_IN_OWNER_MODE"));

        int before = version(session);
        long seqBefore = aiInputSeq(session);
        postAs(ownerToken, session.ownerPath("/takeover"), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.handlerMode").value("OWNER"))
                .andExpect(jsonPath("$.result.status").value("COLLECTING"))
                .andExpect(jsonPath("$.result.version").value(before + 1));
        assertThat(aiInputSeq(session)).isEqualTo(seqBefore + 1);
        // 이미 직접 응대 중이면 그대로
        postAs(ownerToken, session.ownerPath("/takeover"), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value(before + 1));

        events.clear();
        postAs(session.token(), session.path("/messages"), customerMessage("c-2", "언제 오세요?"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.aiStatus").value("NONE"));
        assertThat(events.stream(AiAnalysisRequested.class)).isEmpty();

        String sent = postAs(ownerToken, session.ownerPath("/messages"), ownerMessage("m-1", "내일 오전에 갈게요"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.sender").value("OWNER"))
                .andExpect(jsonPath("$.result.deliveryStatus").value("SENT"))
                .andReturn().getResponse().getContentAsString();
        long messageId = ((Number) JsonPath.read(sent, "$.result.messageId")).longValue();
        postAs(ownerToken, session.ownerPath("/messages"), ownerMessage("m-1", "내일 오전에 갈게요"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.messageId").value(messageId));
        postAs(ownerToken, session.ownerPath("/messages"), ownerMessage("c-2", "같은 ID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("clientMsgId"));

        getAs(session.token(), session.path("/messages"))
                .andExpect(jsonPath("$.result.messages[*].sender", contains("CUSTOMER", "CUSTOMER", "OWNER")))
                .andExpect(jsonPath("$.result.messages[2].content").value("내일 오전에 갈게요"));
        getAs(session.token(), session.path(""))
                .andExpect(jsonPath("$.result.handlerMode").value("OWNER"))
                .andExpect(jsonPath("$.result.nextAction").value(nullValue()));
    }

    @Test
    @DisplayName("AI 재개 - 안전 신호·필수 항목 판단 대기면 422, 참고 항목 미확인은 막지 않음, 사장님 확인이면 정보 수집부터")
    void resumeAi() throws Exception {
        Session session = startInquiry();
        send(session, "물이 새요");
        postAs(session.token(), session.path("/handoff-requests"), "").andExpect(status().isCreated());
        postAs(ownerToken, session.ownerPath("/takeover"), "").andExpect(status().isOk());
        assertThat(handoff(session)).containsEntry("status", "IN_PROGRESS");

        jdbc.update("UPDATE inquiry SET safety_flag = true WHERE id = ?", session.inquiryId());
        postAs(ownerToken, session.ownerPath("/resume-ai"), "")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("AI_RESUME_BLOCKED"))
                .andExpect(jsonPath("$.result.reasons", contains("SAFETY")));
        jdbc.update("UPDATE inquiry SET safety_flag = false WHERE id = ?", session.inquiryId());

        // 사장님이 정한 증상·모델을 고객이 다른 값으로 고침 → 둘 다 미확인(CONFLICT), 증상만 필수
        patchAs(ownerToken, session.ownerPath(""),
                ownerFields(version(session), field("symptom", "누수"), field("model", "NCB-500")))
                .andExpect(status().isOk());
        patchAs(session.token(), session.path("/fields"), customerFields(version(session),
                field("symptom", "온수 안 나옴"), field("model", "NCB-300"))).andExpect(status().isOk());
        postAs(ownerToken, session.ownerPath("/resume-ai"), "")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.result.reasons", contains("REQUIRED_FIELD")));
        assertThat(inquiry(session)).containsEntry("handler_mode", "OWNER");

        patchAs(ownerToken, session.ownerPath(""), ownerFields(version(session), field("symptom", "온수 안 나옴")))
                .andExpect(status().isOk());
        int before = version(session);
        long seqBefore = aiInputSeq(session);
        events.clear();
        postAs(ownerToken, session.ownerPath("/resume-ai"), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.handlerMode").value("AI"))
                .andExpect(jsonPath("$.result.status").value("COLLECTING"))
                .andExpect(jsonPath("$.result.version").value(before + 1));
        assertThat(aiInputSeq(session)).isEqualTo(seqBefore + 1);
        assertThat(handoff(session)).containsEntry("status", "DONE");
        assertThat(handoff(session).get("resolved_at")).isNotNull();
        // AI에게 바로 맡김 - 답하지 않은 고객 메시지가 있어 분석할 내용이 있음
        assertThat(events.stream(AiAnalysisRequested.class)).hasSize(1);
        assertThat(aiStore.prepare(session.inquiryId())).isPresent();
        // 이미 AI가 답하는 중이면 그대로
        postAs(ownerToken, session.ownerPath("/resume-ai"), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value(before + 1));
    }

    @Test
    @DisplayName("확인 필요 이유 - 사장님 확인 단계에서만, 안전 > 고객 요청 > 필수 항목 > AI가 넘김 순서로 하나, 참고 항목은 이유가 아님")
    void ownerCheckReason() throws Exception {
        Session session = startInquiry();
        send(session, "물이 새요");
        assertCheckReason(session, null);

        jdbc.update("UPDATE inquiry SET status = 'OWNER_CHECK' WHERE id = ?", session.inquiryId());
        assertCheckReason(session, "AI_HANDOFF");

        // 참고 항목(모델)만 서로 다르면 AI가 넘긴 그대로
        patchAs(ownerToken, session.ownerPath(""), ownerFields(version(session), field("model", "NCB-500")))
                .andExpect(status().isOk());
        patchAs(session.token(), session.path("/fields"), customerFields(version(session), field("model", "NCB-300")))
                .andExpect(status().isOk());
        assertCheckReason(session, "AI_HANDOFF");

        patchAs(ownerToken, session.ownerPath(""), ownerFields(version(session), field("symptom", "누수")))
                .andExpect(status().isOk());
        patchAs(session.token(), session.path("/fields"), customerFields(version(session), field("symptom", "온수 안 나옴")))
                .andExpect(status().isOk());
        assertCheckReason(session, "REQUIRED_INFO_MISSING");

        postAs(session.token(), session.path("/handoff-requests"), "").andExpect(status().isCreated());
        assertCheckReason(session, "CUSTOMER_REQUEST");

        jdbc.update("UPDATE inquiry SET safety_flag = true WHERE id = ?", session.inquiryId());
        assertCheckReason(session, "SAFETY");

        jdbc.update("UPDATE inquiry SET status = 'SCHEDULING' WHERE id = ?", session.inquiryId());
        assertCheckReason(session, null);
    }

    @Test
    @DisplayName("메시지 ID - 다른 쪽이 쓴 ID로 보내면 400, 그 메시지를 돌려주지 않음")
    void messageIdBelongsToSender() throws Exception {
        Session session = startInquiry();
        postAs(ownerToken, session.ownerPath("/takeover"), "").andExpect(status().isOk());
        postAs(ownerToken, session.ownerPath("/messages"), ownerMessage("same-1", "사진 보내 주세요"))
                .andExpect(status().isCreated());
        postAs(session.token(), session.path("/messages"), customerMessage("same-1", "네"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("clientMsgId"));

        postAs(session.token(), session.path("/messages"), customerMessage("same-2", "보냈어요"))
                .andExpect(status().isCreated());
        postAs(ownerToken, session.ownerPath("/messages"), ownerMessage("same-2", "확인했어요"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("clientMsgId"));
    }

    @Test
    @DisplayName("AI 재개 뒤 남은 전화 회신 요청 - 고객이 다시 요청하면 AI를 멈추고 다시 알림, 또 보내도 그대로")
    void askAgainAfterResume() throws Exception {
        Session session = startInquiry();
        postAs(session.token(), session.path("/callback-requests"), callback("김고객", "010-2222-3333"))
                .andExpect(status().isCreated());
        assertThat(notifications(session)).isEqualTo(1);
        postAs(ownerToken, session.ownerPath("/takeover"), "").andExpect(status().isOk());
        postAs(ownerToken, session.ownerPath("/resume-ai"), "").andExpect(status().isOk());
        assertThat(inquiry(session)).containsEntry("status", "COLLECTING");
        assertThat(handoff(session)).containsEntry("status", "WAITING");

        postAs(session.token(), session.path("/handoff-requests"), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.requestType").value("CALLBACK"));
        assertThat(inquiry(session)).containsEntry("status", "OWNER_CHECK");
        assertThat(notifications(session)).isEqualTo(2);

        int before = version(session);
        postAs(session.token(), session.path("/handoff-requests"), "").andExpect(status().isOk());
        postAs(session.token(), session.path("/callback-requests"), callback("김고객", "010-2222-3333"))
                .andExpect(status().isOk());
        assertThat(version(session)).isEqualTo(before);
        assertThat(notifications(session)).isEqualTo(2);
    }

    @Test
    @DisplayName("AI 재개 - 사장님이 이미 답한 고객 메시지에는 AI가 다시 답하지 않고 다음 고객 메시지부터 답함")
    void resumeAfterOwnerReply() throws Exception {
        Session session = startInquiry();
        send(session, "물이 새요");
        postAs(ownerToken, session.ownerPath("/takeover"), "").andExpect(status().isOk());
        postAs(ownerToken, session.ownerPath("/messages"), ownerMessage("m-1", "사진 한 장 보내 주세요"))
                .andExpect(status().isCreated());
        postAs(ownerToken, session.ownerPath("/resume-ai"), "").andExpect(status().isOk());
        assertThat(aiStore.prepare(session.inquiryId())).isEmpty();

        postAs(session.token(), session.path("/messages"), customerMessage("c-2", "보냈어요"))
                .andExpect(jsonPath("$.result.aiStatus").value("PROCESSING"));
        assertThat(aiStore.prepare(session.inquiryId())).isPresent();
    }

    @Test
    @DisplayName("문의 종료 - 예약은 그대로(reservationKept), 진행 중 요청은 끝냄, 그 뒤 쓰기는 409, 다시 종료는 200")
    void close() throws Exception {
        Session session = startInquiry();
        postAs(session.token(), session.path("/callback-requests"), callback("김고객", "010-9876-5432"))
                .andExpect(status().isCreated());
        long reservationId = reservation(session.inquiryId(), "CONFIRMED", at(LocalDate.now(KST).plusDays(1), 10));
        int before = version(session);

        postAs(ownerToken, session.ownerPath("/close"), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("CLOSED"))
                .andExpect(jsonPath("$.result.version").value(before + 1))
                .andExpect(jsonPath("$.result.reservationKept").value(true))
                .andExpect(jsonPath("$.result.reservationId").value(reservationId));
        assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservationId))
                .isEqualTo("CONFIRMED");
        assertThat(handoff(session)).containsEntry("status", "DONE");

        postAs(ownerToken, session.ownerPath("/close"), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value(before + 1));
        postAs(session.token(), session.path("/messages"), customerMessage("c-1", "안녕하세요"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INQUIRY_CLOSED"));
        postAs(session.token(), session.path("/handoff-requests"), "")
                .andExpect(status().isConflict());
        for (String action : new String[]{"/takeover", "/resume-ai"}) {
            postAs(ownerToken, session.ownerPath(action), "")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INQUIRY_CLOSED"));
        }

        Session noReservation = startInquiry();
        postAs(ownerToken, noReservation.ownerPath("/close"), "")
                .andExpect(jsonPath("$.result.reservationKept").value(false))
                .andExpect(jsonPath("$.result.reservationId").value(nullValue()));
    }

    @Test
    @DisplayName("직접 상담 요청 - 사장님 확인·알림, 진행 중 요청이 있으면 200으로 그 요청, 고객 화면에 보임")
    void directChatRequest() throws Exception {
        Session session = startInquiry();
        int before = version(session);
        String body = postAs(session.token(), session.path("/handoff-requests"), "")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.requestType").value("DIRECT_CHAT"))
                .andExpect(jsonPath("$.result.status").value("WAITING"))
                .andReturn().getResponse().getContentAsString();
        long handoffId = ((Number) JsonPath.read(body, "$.result.handoffId")).longValue();
        assertThat(inquiry(session)).containsEntry("status", "OWNER_CHECK").containsEntry("version", before + 1);
        assertThat(notifications(session)).isEqualTo(1);

        postAs(session.token(), session.path("/handoff-requests"), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.handoffId").value(handoffId));
        assertThat(notifications(session)).isEqualTo(1);
        getAs(session.token(), session.path(""))
                .andExpect(jsonPath("$.result.handoff.handoffId").value(handoffId))
                .andExpect(jsonPath("$.result.handoff.requestType").value("DIRECT_CHAT"));
        // 사장님 확인 단계라 AI가 답하지 않음
        postAs(session.token(), session.path("/messages"), customerMessage("c-1", "빨리 와 주세요"))
                .andExpect(jsonPath("$.result.aiStatus").value("NONE"));
        postAs(ownerToken, session.path("/handoff-requests"), "").andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("전화 회신 요청 - 회신 요청에만 저장(고객 번호·인증 그대로), 대기 중 직접 상담은 같은 요청을 회신으로")
    void callbackRequest() throws Exception {
        Session session = startInquiry();
        putAs(session.token(), session.path("/visit-info"), visitInfo(version(session), "김고객", "010-2222-3333"))
                .andExpect(status().isOk());
        jdbc.update("UPDATE inquiry SET phone_verified_at = now() WHERE id = ?", session.inquiryId());
        String body = postAs(session.token(), session.path("/handoff-requests"), "")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long handoffId = ((Number) JsonPath.read(body, "$.result.handoffId")).longValue();

        postAs(session.token(), session.path("/callback-requests"), callback(" 김고객 ", "010-9876-5432"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.handoffId").value(handoffId))
                .andExpect(jsonPath("$.result.requestType").value("CALLBACK"))
                .andExpect(jsonPath("$.result.phone").doesNotExist());
        assertThat(handoff(session)).containsEntry("request_type", "CALLBACK").containsEntry("name", "김고객")
                .containsEntry("phone", "01098765432");
        assertThat(notifications(session)).isEqualTo(2);
        Map<String, Object> customer = jdbc.queryForMap("SELECT c.phone, i.phone_verified_at FROM inquiry i "
                + "JOIN customer c ON c.id = i.customer_id WHERE i.id = ?", session.inquiryId());
        assertThat(customer.get("phone")).isEqualTo("01022223333");
        assertThat(customer.get("phone_verified_at")).isNotNull();

        // 진행 중 회신 요청이 있으면 바꾸지 않고 그 요청을 돌려줌
        postAs(session.token(), session.path("/callback-requests"), callback("김고객", "010-1111-2222"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.handoffId").value(handoffId));
        assertThat(handoff(session)).containsEntry("phone", "01098765432");
        assertThat(notifications(session)).isEqualTo(2);

        postAs(session.token(), session.path("/callback-requests"), callback("김고객", "02-123-4567"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("phone"));
        postAs(session.token(), session.path("/callback-requests"), "{\"name\":\" \",\"phone\":null}")
                .andExpect(status().isBadRequest());

        Session fresh = startInquiry();
        postAs(fresh.token(), fresh.path("/callback-requests"), callback("박고객", "010-3333-4444"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.requestType").value("CALLBACK"))
                .andExpect(jsonPath("$.result.status").value("WAITING"));
    }

    @Test
    @DisplayName("요청 처리 상태 - 처리 중·완료(끝낸 시각), 끝낸 요청은 되돌리지 않음, 다른 업체 요청은 404")
    void updateHandoff() throws Exception {
        Session session = startInquiry();
        String body = postAs(session.token(), session.path("/handoff-requests"), "")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long handoffId = ((Number) JsonPath.read(body, "$.result.handoffId")).longValue();
        String path = "/api/v1/owner/handoff-requests/" + handoffId;

        patchAs(ownerToken, path, "{\"status\":\"WAITING\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("status"));
        int before = version(session);
        patchAs(ownerToken, path, "{\"status\":\"IN_PROGRESS\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.inquiryId").value(session.inquiryId()))
                .andExpect(jsonPath("$.result.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.result.resolvedAt").value(nullValue()));
        assertThat(version(session)).isEqualTo(before + 1);
        patchAs(ownerToken, path, "{\"status\":\"DONE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("DONE"))
                .andExpect(jsonPath("$.result.resolvedAt").value(notNullValue()));
        patchAs(ownerToken, path, "{\"status\":\"DONE\"}").andExpect(status().isOk());
        patchAs(ownerToken, path, "{\"status\":\"IN_PROGRESS\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HANDOFF_ALREADY_DONE"));

        String otherToken = accounts.ownerToken(accounts.approvedOwner(accounts.newPhone()));
        patchAs(otherToken, path, "{\"status\":\"DONE\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HANDOFF_NOT_FOUND"));
        patchAs(ownerToken, "/api/v1/owner/handoff-requests/0", "{\"status\":\"DONE\"}")
                .andExpect(status().isNotFound());

        // 끝낸 뒤에는 고객이 새로 요청
        postAs(session.token(), session.path("/handoff-requests"), "")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.handoffId").value(not((int) handoffId)));
    }

    // ---------- 도움 함수 ----------

    private Session startInquiry() throws Exception {
        return startInquiry(ownerId);
    }

    private Session startInquiry(long owner) throws Exception {
        String body = mockMvc.perform(post("/api/v1/public/intake/" + accounts.linkToken(owner) + "/inquiries")
                        .contentType(MediaType.APPLICATION_JSON).content(ALL_CONSENTS))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Session(((Number) JsonPath.read(body, "$.result.inquiryId")).longValue(),
                JsonPath.read(body, "$.result.inquiryToken"));
    }

    private long send(Session session, String content) throws Exception {
        String body = postAs(session.token(), session.path("/messages"),
                customerMessage(UUID.randomUUID().toString(), content))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.result.messageId")).longValue();
    }

    private void assertCheckReason(Session session, String reason) throws Exception {
        getAs(ownerToken, session.ownerPath(""))
                .andExpect(jsonPath("$.result.ownerCheckReason").value(reason));
        getAs(ownerToken, "/api/v1/owner/inquiries")
                .andExpect(jsonPath("$.result.content[0].inquiryId").value(session.inquiryId()))
                .andExpect(jsonPath("$.result.content[0].ownerCheckReason").value(reason));
    }

    private long donePhoto(Session session) {
        return jdbc.queryForObject("INSERT INTO photo (inquiry_id, s3_key_original, mime_type, size_bytes, "
                + "upload_status) VALUES (?, 'k/done.jpg', 'image/jpeg', 100, 'DONE') RETURNING id", Long.class,
                session.inquiryId());
    }

    private long reservation(Long inquiryId, String status, OffsetDateTime start) {
        return jdbc.queryForObject("INSERT INTO reservation (inquiry_id, owner_id, start_at, end_at, address, status) "
                        + "VALUES (?, ?, ?, ?, '경기 고양시 덕양구 화정로 1', ?) RETURNING id", Long.class,
                inquiryId, ownerId, start, start.plusHours(1), status);
    }

    private static OffsetDateTime at(LocalDate date, int hour) {
        return date.atTime(hour, 0).atOffset(ZoneOffset.ofHours(9));
    }

    private int version(Session session) {
        return jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?", Integer.class, session.inquiryId());
    }

    private long aiInputSeq(Session session) {
        return jdbc.queryForObject("SELECT ai_input_seq FROM inquiry WHERE id = ?", Long.class, session.inquiryId());
    }

    private Map<String, Object> inquiry(Session session) {
        return jdbc.queryForMap("SELECT status, handler_mode, version FROM inquiry WHERE id = ?", session.inquiryId());
    }

    // 문의의 마지막 상담 요청
    private Map<String, Object> handoff(Session session) {
        return jdbc.queryForMap("SELECT request_type, name, phone, status, resolved_at FROM handoff_request "
                + "WHERE inquiry_id = ? ORDER BY id DESC LIMIT 1", session.inquiryId());
    }

    private int fieldRows(Session session, String fieldKey) {
        return jdbc.queryForObject("SELECT count(*) FROM inquiry_field WHERE inquiry_id = ? AND field_key = ?",
                Integer.class, session.inquiryId(), fieldKey);
    }

    private int notifications(Session session) {
        return jdbc.queryForObject("SELECT count(*) FROM notification WHERE ref_type = 'INQUIRY' AND ref_id = ? "
                + "AND event_type = 'NEEDS_CHECK'", Integer.class, session.inquiryId());
    }

    private static String customerMessage(String clientMsgId, String content) {
        return "{\"clientMsgId\":\"" + clientMsgId + "\",\"content\":\"" + content + "\"}";
    }

    private static String ownerMessage(String clientMsgId, String content) {
        return customerMessage(clientMsgId, content);
    }

    private static String callback(String name, String phone) {
        return "{\"name\":\"" + name + "\",\"phone\":\"" + phone + "\"}";
    }

    private static String field(String fieldKey, String value) {
        return "{\"fieldKey\":\"" + fieldKey + "\",\"value\":\"" + value + "\"}";
    }

    private static String customerFields(int version, String... items) {
        return "{\"version\":" + version + ",\"fields\":[" + String.join(",", items) + "]}";
    }

    private static String ownerFields(int version, String... items) {
        return customerFields(version, items);
    }

    private static String visitInfo(int version, String name, String phone) {
        return """
                {"version":%d,"name":"%s","phone":"%s","address":"경기 고양시 덕양구 화정로 1",
                 "addressDetail":"101동 202호","lat":37.634,"lng":126.832,"accessNote":"주차는 지하 2층",
                 "workCategory":"REPAIR"}""".formatted(version, name, phone);
    }

    private static String ownerVisit(int version, String name, String phone, String address, String workCategory) {
        return """
                {"version":%d,"visitInfo":{"name":"%s","phone":"%s","address":"%s","addressDetail":"101동 202호",
                 "lat":37.634,"lng":126.832,"accessNote":null,"workCategory":%s}}""".formatted(version, name, phone,
                address, workCategory == null ? "null" : "\"" + workCategory + "\"");
    }

    private ResultActions getAs(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions postAs(String token, String path, String json) throws Exception {
        return mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions putAs(String token, String path, String json) throws Exception {
        return mockMvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions patchAs(String token, String path, String json) throws Exception {
        return mockMvc.perform(patch(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
