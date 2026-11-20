package com.ilro.backend.domain.admin;

import com.ilro.backend.domain.inquiry.service.IntakeTerms;
import com.ilro.backend.infra.storage.LocalPhotoStorage;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 개인정보 삭제·접근 기록 조회 (O-09, 명세 1.8·4.8·5.9, BE-19)
// 삭제는 문의 단위: 대화·사진·접수 항목·방문 주소는 지우고, 동의 기록과 예약 기록은 남김
// 접근 기록은 서버 로그(AccessLog)가 전부 - 테스트는 이 서버가 남긴 기록(메모리)을 읽음
@SpringBootTest
class AdminPrivacyFlowTest {

    private static final String DELETIONS = "/api/v1/admin/privacy/deletions";
    private static final String ACCESS_LOGS = "/api/v1/admin/access-logs";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private LocalPhotoStorage storage;
    @Autowired
    private IntakeTerms intakeTerms;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private long ownerId;
    private String ownerToken;
    private long superId;
    private String superAdmin;
    private String staff;

    private record Seeded(long inquiryId, String token, long customerId, String originalKey, String resizedKey) {
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        accounts = new TestAccounts(jdbc);
        ownerId = accounts.approvedOwner(accounts.newPhone());
        ownerToken = accounts.ownerToken(ownerId);
        superId = accounts.admin(accounts.newPhone());
        superAdmin = accounts.adminToken(superId, "SUPER");
        staff = accounts.adminToken(accounts.admin(accounts.newPhone()), "STAFF");
    }

    @AfterEach
    void tearDown() {
        accounts.cleanUp();
    }

    @Test
    @DisplayName("삭제 - 대화·사진·접수 항목·방문 주소·고객 이름·번호를 지우고 동의 기록은 남김, 다시 보내도 같은 결과")
    void deleteInquiry() throws Exception {
        Seeded seeded = closedInquiry();
        long id = seeded.inquiryId();
        // 지우기 전에는 사장님·고객 모두 볼 수 있음
        getAs(ownerToken, "/api/v1/owner/inquiries/" + id).andExpect(status().isOk());
        getAs(seeded.token(), "/api/v1/customer/inquiries/" + id).andExpect(status().isOk());

        delete(superAdmin, body("CUSTOMER_REQUEST", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.deleted", hasSize(1)))
                .andExpect(jsonPath("$.result.deleted[0].inquiryId").value(id))
                .andExpect(jsonPath("$.result.deleted[0].messages").value(3))
                .andExpect(jsonPath("$.result.deleted[0].photos").value(1))
                .andExpect(jsonPath("$.result.deleted[0].fields").value(2))
                .andExpect(jsonPath("$.result.deleted[0].customerDeleted").value(true))
                .andExpect(jsonPath("$.result.deleted[0].kept", contains("CONSENT_RECORD")))
                .andExpect(jsonPath("$.result.failed", hasSize(0)));

        // 대화는 줄만 남고 내용이 없음, AI에 보낸 대화·응답 원문도 없음
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message WHERE inquiry_id = ? AND content IS NOT NULL",
                Integer.class, id)).isZero();
        assertThat(count("message", id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_decision WHERE inquiry_id = ? "
                + "AND (input_snapshot IS NOT NULL OR output_raw IS NOT NULL)", Integer.class, id)).isZero();
        // 사진은 파일도 기록도 없음
        assertThat(count("photo", id)).isZero();
        assertThat(storage.find(seeded.originalKey())).isEmpty();
        assertThat(storage.find(seeded.resizedKey())).isEmpty();
        assertThat(count("inquiry_field", id)).isZero();
        assertThat(count("handoff_request", id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM phone_verification WHERE target_inquiry_id = ?",
                Integer.class, id)).isZero();
        // 문의는 지웠다는 표시만 - 방문 주소·증상·제품 정보·요약·고객 연결이 비어 있음
        assertThat(inquiry(id)).containsEntry("deleted", true).containsEntry("status", "CLOSED")
                .containsEntry("customer_id", null).containsEntry("symptom", null).containsEntry("address", null)
                .containsEntry("address_detail", null).containsEntry("lat", null).containsEntry("access_note", null)
                .containsEntry("model", null).containsEntry("ai_summary", null)
                .containsEntry("phone_verified_at", null);
        // 다른 예약·문의가 없는 고객은 이름·번호까지 지움, 동의 기록은 남김
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer WHERE id = ?", Integer.class,
                seeded.customerId())).isZero();
        assertThat(count("consent", id)).isEqualTo(2);
        // 문의 토큰이 끊기고, 사장님에게도 없는 문의
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_token WHERE inquiry_id = ? AND revoked_at IS NULL",
                Integer.class, id)).isZero();
        getAs(seeded.token(), "/api/v1/customer/inquiries/" + id).andExpect(status().isUnauthorized());
        getAs(ownerToken, "/api/v1/owner/inquiries/" + id).andExpect(status().isNotFound());

        // 같은 요청을 다시 - 지울 것이 남아 있지 않음
        delete(superAdmin, body("CUSTOMER_REQUEST", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.deleted[0].messages").value(0))
                .andExpect(jsonPath("$.result.deleted[0].photos").value(0))
                .andExpect(jsonPath("$.result.deleted[0].customerDeleted").value(false));
    }

    @Test
    @DisplayName("예약 기록이 있으면 - 상담 내용은 지우되 예약과 그 고객 이름·번호는 남김 (RESERVATION_RECORD)")
    void keepsReservationRecord() throws Exception {
        Seeded seeded = closedInquiry();
        jdbc.update("UPDATE inquiry SET status = 'BOOKED' WHERE id = ?", seeded.inquiryId());
        long reservationId = reservation(seeded, "COMPLETED");

        delete(superAdmin, body("CUSTOMER_REQUEST", seeded.inquiryId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.deleted[0].messages").value(3))
                .andExpect(jsonPath("$.result.deleted[0].customerDeleted").value(false))
                .andExpect(jsonPath("$.result.deleted[0].kept", contains("RESERVATION_RECORD", "CONSENT_RECORD")));

        assertThat(inquiry(seeded.inquiryId())).containsEntry("deleted", true).containsEntry("address", null)
                .containsEntry("customer_id", null);
        assertThat(jdbc.queryForObject("SELECT r.status || '/' || r.address || '/' || c.name FROM reservation r "
                + "JOIN customer c ON c.id = r.customer_id WHERE r.id = ?", String.class, reservationId))
                .isEqualTo("COMPLETED/경기 고양시 덕양구 화정로 1/김고객");
    }

    @Test
    @DisplayName("지울 수 없음 - 진행 중 상담·진행 중 예약·없는 문의·보관기간 미정이 하나라도 있으면 422 + 이유, 아무것도 지우지 않음")
    void notAllowed() throws Exception {
        Seeded closed = closedInquiry();
        Seeded open = closedInquiry();
        jdbc.update("UPDATE inquiry SET status = 'OWNER_CHECK' WHERE id = ?", open.inquiryId());
        Seeded booked = closedInquiry();
        jdbc.update("UPDATE inquiry SET status = 'BOOKED' WHERE id = ?", booked.inquiryId());
        reservation(booked, "CONFIRMED");

        delete(superAdmin, body("CUSTOMER_REQUEST", closed.inquiryId(), open.inquiryId(), booked.inquiryId(),
                999_999_999L))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PRIVACY_DELETE_NOT_ALLOWED"))
                .andExpect(jsonPath("$.result.reasons", hasSize(3)))
                .andExpect(jsonPath("$.result.reasons[0].inquiryId").value(open.inquiryId()))
                .andExpect(jsonPath("$.result.reasons[0].reason").value("OPEN_INQUIRY"))
                .andExpect(jsonPath("$.result.reasons[1].reason").value("ACTIVE_RESERVATION"))
                .andExpect(jsonPath("$.result.reasons[2].reason").value("INQUIRY_NOT_FOUND"));
        // 지울 수 있는 문의도 그대로
        assertThat(inquiry(closed.inquiryId())).containsEntry("deleted", false)
                .containsEntry("address", "경기 고양시 덕양구 화정로 1");
        assertThat(storage.find(closed.originalKey())).isPresent();
        assertThat(count("inquiry_field", closed.inquiryId())).isEqualTo(2);

        // 보관기간이 지났다는 근거 - 팀이 기간을 정하기 전에는 실행하지 않음
        delete(superAdmin, body("RETENTION_EXPIRED", closed.inquiryId()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.result.reasons[0].reason").value("RETENTION_PERIOD_UNDEFINED"));
        assertThat(inquiry(closed.inquiryId())).containsEntry("deleted", false);
    }

    @Test
    @DisplayName("부분 실패 - 사진 파일 삭제가 실패한 문의는 failed로, 나머지는 지움. 실패한 문의는 그대로 남아 다시 실행하면 지워짐")
    void partialFailure() throws Exception {
        Seeded first = closedInquiry();
        Seeded broken = closedInquiry();
        storage.failDeleteOnceForTest(broken.resizedKey());

        delete(superAdmin, body("CUSTOMER_REQUEST", first.inquiryId(), broken.inquiryId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.deleted", hasSize(1)))
                .andExpect(jsonPath("$.result.deleted[0].inquiryId").value(first.inquiryId()))
                .andExpect(jsonPath("$.result.failed", hasSize(1)))
                .andExpect(jsonPath("$.result.failed[0].inquiryId").value(broken.inquiryId()))
                .andExpect(jsonPath("$.result.failed[0].reason").value("PHOTO_DELETE_FAILED"));
        assertThat(inquiry(first.inquiryId())).containsEntry("deleted", true);
        // 실패한 문의의 기록은 그대로 (사진 파일 하나는 이미 지워졌을 수 있음)
        assertThat(inquiry(broken.inquiryId())).containsEntry("deleted", false)
                .containsEntry("address", "경기 고양시 덕양구 화정로 1");
        assertThat(count("photo", broken.inquiryId())).isEqualTo(1);
        assertThat(count("inquiry_field", broken.inquiryId())).isEqualTo(2);

        // 접근 기록 - 문의마다 결과, 요청 전체는 일부 실패(PARTIAL)
        getAs(superAdmin, ACCESS_LOGS + "?targetType=INQUIRY&targetId=" + broken.inquiryId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].action").value("DELETE"))
                .andExpect(jsonPath("$.result.content[0].actorType").value("ADMIN"))
                .andExpect(jsonPath("$.result.content[0].actorId").value(superId))
                .andExpect(jsonPath("$.result.content[0].result").value("FAILED"));
        getAs(superAdmin, ACCESS_LOGS + "?targetType=privacy_deletion")
                .andExpect(jsonPath("$.result.content[?(@.actorId == " + superId + ")].result", hasItem("PARTIAL")));

        // 다시 실행하면 지워짐
        delete(superAdmin, body("CUSTOMER_REQUEST", broken.inquiryId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.deleted[0].photos").value(1))
                .andExpect(jsonPath("$.result.failed", hasSize(0)));
        assertThat(storage.find(broken.originalKey())).isEmpty();
        assertThat(inquiry(broken.inquiryId())).containsEntry("deleted", true);
    }

    @Test
    @DisplayName("삭제 요청 거절 - SUPER 운영자만(STAFF 403·사장님 토큰 403), 문의 없음·근거 없음·참조 값 형식 400")
    void deleteRejected() throws Exception {
        Seeded seeded = closedInquiry();
        delete(staff, body("CUSTOMER_REQUEST", seeded.inquiryId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_ROLE_REQUIRED"));
        delete(ownerToken, body("CUSTOMER_REQUEST", seeded.inquiryId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        delete(superAdmin, "{\"inquiryIds\":[],\"basis\":\"CUSTOMER_REQUEST\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("inquiryIds"));
        // 처리 근거가 없으면 실행하지 않음
        delete(superAdmin, "{\"inquiryIds\":[" + seeded.inquiryId() + "]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("basis"));
        delete(superAdmin, "{\"inquiryIds\":[" + seeded.inquiryId() + "],\"basis\":\"JUST_BECAUSE\"}")
                .andExpect(status().isBadRequest());
        // 참조 값에는 이름·번호가 들어가지 않게 영문·숫자만
        delete(superAdmin, body("CUSTOMER_REQUEST", seeded.inquiryId()).replace("REQ-2026-001", "김고객 요청"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("reference"));
        assertThat(inquiry(seeded.inquiryId())).containsEntry("deleted", false);
    }

    @Test
    @DisplayName("접근 기록 - 사장님이 본 문의가 기록으로 남고 SUPER 운영자가 대상별로 조회, 조회한 사실도 기록")
    void accessLogs() throws Exception {
        Seeded seeded = closedInquiry();
        long id = seeded.inquiryId();
        getAs(ownerToken, "/api/v1/owner/inquiries/" + id).andExpect(status().isOk());
        getAs(ownerToken, "/api/v1/owner/inquiries/" + id).andExpect(status().isOk());

        String body = getAs(superAdmin, ACCESS_LOGS + "?targetType=INQUIRY&targetId=" + id + "&from=" + today()
                + "&to=" + today())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(2)))
                .andExpect(jsonPath("$.result.content[0].at").value(endsWith("+09:00")))
                .andExpect(jsonPath("$.result.content[0].actorType").value("OWNER"))
                .andExpect(jsonPath("$.result.content[0].actorId").value(ownerId))
                .andExpect(jsonPath("$.result.content[0].action").value("VIEW"))
                .andExpect(jsonPath("$.result.content[0].targetType").value("INQUIRY"))
                .andExpect(jsonPath("$.result.content[0].targetId").value(id))
                .andExpect(jsonPath("$.result.content[0].result").value("SUCCESS"))
                .andExpect(jsonPath("$.result.page").value(0))
                .andExpect(jsonPath("$.result.size").value(50))
                .andExpect(jsonPath("$.result.totalElements").value(2))
                .andExpect(jsonPath("$.result.isLast").value(true))
                .andExpect(jsonPath("$.result.truncated").value(false))
                .andReturn().getResponse().getContentAsString();
        // 기록에 전화번호·주소·대화가 없음
        assertThat(body).doesNotContain("화정로").doesNotContain("김고객").doesNotContain("온수");

        // 쪽 나누기, 날짜를 안 보내면 오늘까지 7일
        getAs(superAdmin, ACCESS_LOGS + "?targetType=inquiry&targetId=" + id + "&page=1&size=1")
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.totalPages").value(2))
                .andExpect(jsonPath("$.result.isLast").value(true));
        // 다른 대상은 섞이지 않음
        getAs(superAdmin, ACCESS_LOGS + "?targetType=RESERVATION&targetId=" + id)
                .andExpect(jsonPath("$.result.content", hasSize(0)));
        // 접근 기록을 본 것도 기록으로 남음 (어느 대상을 봤는지와 함께)
        getAs(superAdmin, ACCESS_LOGS + "?targetType=access_log&targetId=" + id)
                .andExpect(jsonPath("$.result.content[0].actorType").value("ADMIN"))
                .andExpect(jsonPath("$.result.content[0].actorId").value(superId))
                .andExpect(jsonPath("$.result.content[0].action").value("VIEW"));
    }

    @Test
    @DisplayName("접근 기록 조회 거절 - SUPER 운영자만, 31일 넘는 기간·뒤집힌 기간·대상 종류 없는 대상 번호·쪽 크기 400")
    void accessLogsRejected() throws Exception {
        getAs(staff, ACCESS_LOGS)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_ROLE_REQUIRED"));
        getAs(ownerToken, ACCESS_LOGS).andExpect(status().isForbidden());
        mockMvc.perform(get(ACCESS_LOGS)).andExpect(status().isUnauthorized());

        getAs(superAdmin, ACCESS_LOGS + "?from=2026-10-01&to=2026-11-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result[0].field").value("to"));
        // 31일은 됨
        getAs(superAdmin, ACCESS_LOGS + "?from=2026-10-01&to=2026-10-31").andExpect(status().isOk());
        getAs(superAdmin, ACCESS_LOGS + "?from=2026-10-10&to=2026-10-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("from"));
        getAs(superAdmin, ACCESS_LOGS + "?targetId=1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("targetType"));
        getAs(superAdmin, ACCESS_LOGS + "?targetType=inquiry:1%20*")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("targetType"));
        getAs(superAdmin, ACCESS_LOGS + "?size=101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("size"));
        getAs(superAdmin, ACCESS_LOGS + "?from=not-a-date").andExpect(status().isBadRequest());
    }

    // ---------- 도움 함수 ----------

    // 상담이 끝난(CLOSED) 문의 - 고객·방문정보·대화 3건·사진 1장·접수 항목 2개·회신 요청·문자 인증·AI 기록
    private Seeded closedInquiry() throws Exception {
        String body = mockMvc.perform(post("/api/v1/public/intake/" + accounts.linkToken(ownerId) + "/inquiries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consents\":[{\"type\":\"PRIVACY_REQUIRED\",\"termsVersion\":\""
                                + intakeTerms.version() + "\"},{\"type\":\"PHOTO\",\"termsVersion\":\""
                                + intakeTerms.version() + "\"}]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long inquiryId = ((Number) JsonPath.read(body, "$.result.inquiryId")).longValue();
        String token = JsonPath.read(body, "$.result.inquiryToken");
        String phone = accounts.newPhone();
        long customerId = jdbc.queryForObject("INSERT INTO customer (owner_id, name, phone) VALUES (?, '김고객', ?) "
                + "RETURNING id", Long.class, ownerId, phone);
        jdbc.update("UPDATE inquiry SET customer_id = ?, status = 'CLOSED', symptom = '온수가 안 나와요', "
                + "address = '경기 고양시 덕양구 화정로 1', address_detail = '101동 1001호', lat = 37.634, "
                + "lng = 126.832, access_note = '공동현관 1234', model = 'NCB-500', ai_summary = '온수 고장 요약', "
                + "phone_verified_at = CURRENT_TIMESTAMP WHERE id = ?", customerId, inquiryId);
        long messageId = jdbc.queryForObject("INSERT INTO message (inquiry_id, sender, content, delivery_status) "
                + "VALUES (?, 'CUSTOMER', '온수가 안 나와요. 주소는 화정로 1이에요', 'SENT') RETURNING id", Long.class,
                inquiryId);
        jdbc.update("INSERT INTO message (inquiry_id, sender, content, delivery_status) VALUES "
                + "(?, 'CUSTOMER', '사진 보내요', 'SENT'), (?, 'OWNER', '내일 방문할게요', 'SENT')", inquiryId, inquiryId);
        String originalKey = "photos/" + UUID.randomUUID() + "/original.jpg";
        String resizedKey = "photos/" + UUID.randomUUID() + "/resized.jpg";
        long photoId = jdbc.queryForObject("INSERT INTO photo (inquiry_id, message_id, s3_key_original, "
                + "s3_key_resized, mime_type, size_bytes, upload_status) VALUES (?, ?, ?, ?, 'image/jpeg', 1000, "
                + "'DONE') RETURNING id", Long.class, inquiryId, messageId, originalKey, resizedKey);
        storage.putForTest(originalKey, 1000, "image/jpeg");
        storage.putForTest(resizedKey, 500, "image/jpeg");
        jdbc.update("INSERT INTO inquiry_field (inquiry_id, field_key, field_value, source, status, message_id) "
                + "VALUES (?, 'symptom', '온수가 안 나와요', 'CUSTOMER', 'CONFIRMED', ?)", inquiryId, messageId);
        jdbc.update("INSERT INTO inquiry_field (inquiry_id, field_key, field_value, source, status, photo_id) "
                + "VALUES (?, 'model', 'NCB-500', 'PHOTO', 'CONFIRMED', ?)", inquiryId, photoId);
        jdbc.update("INSERT INTO handoff_request (inquiry_id, request_type, name, phone, status) "
                + "VALUES (?, 'CALLBACK', '김고객', ?, 'DONE')", inquiryId, phone);
        jdbc.update("INSERT INTO phone_verification (phone, purpose, code_hash, send_status, expires_at, "
                + "verified_at, target_inquiry_id) VALUES (?, 'CUSTOMER_VERIFY', 'seeded', 'SENT', "
                + "CURRENT_TIMESTAMP + interval '5 minutes', CURRENT_TIMESTAMP, ?)", phone, inquiryId);
        jdbc.update("INSERT INTO ai_decision (inquiry_id, message_id, stage, action, decided_by, status, "
                + "input_snapshot, output_raw) VALUES (?, ?, 'DECIDE', 'ASK_MORE_INFO', 'EXTERNAL_API', 'SUCCESS', "
                + "'{\"messages\":[{\"content\":\"온수가 안 나와요\"}]}'::jsonb, '{\"result\":{}}'::jsonb)", inquiryId,
                messageId);
        return new Seeded(inquiryId, token, customerId, originalKey, resizedKey);
    }

    private long reservation(Seeded seeded, String status) {
        return jdbc.queryForObject("INSERT INTO reservation (inquiry_id, owner_id, customer_id, start_at, end_at, "
                + "address, status) VALUES (?, ?, ?, CURRENT_TIMESTAMP - interval '3 days', CURRENT_TIMESTAMP "
                + "- interval '3 days' + interval '1 hour', '경기 고양시 덕양구 화정로 1', ?) RETURNING id", Long.class,
                seeded.inquiryId(), ownerId, seeded.customerId(), status);
    }

    private static String body(String basis, long... inquiryIds) {
        StringBuilder ids = new StringBuilder();
        for (long inquiryId : inquiryIds) {
            ids.append(ids.isEmpty() ? "" : ",").append(inquiryId);
        }
        return "{\"inquiryIds\":[" + ids + "],\"basis\":\"" + basis + "\",\"reference\":\"REQ-2026-001\"}";
    }

    private ResultActions delete(String token, String body) throws Exception {
        return mockMvc.perform(as(token, post(DELETIONS)).content(body));
    }

    private ResultActions getAs(String token, String path) throws Exception {
        return mockMvc.perform(as(token, get(path)));
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
    }

    private int count(String table, long inquiryId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE inquiry_id = ?", Integer.class,
                inquiryId);
    }

    private Map<String, Object> inquiry(long inquiryId) {
        return jdbc.queryForMap("SELECT status, deleted_at IS NOT NULL AS deleted, customer_id, symptom, address, "
                + "address_detail, lat, access_note, model, ai_summary, phone_verified_at FROM inquiry WHERE id = ?",
                inquiryId);
    }

    private static String today() {
        return LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
    }
}
