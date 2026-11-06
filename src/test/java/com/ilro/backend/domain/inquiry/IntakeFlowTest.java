package com.ilro.backend.domain.inquiry;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 고객 접수·동의·대화·접수정보 API (BE-05, 명세 4.2) - 실제 문의 토큰으로 호출
@SpringBootTest
class IntakeFlowTest {

    private static final String TERMS = "2026-10-v1";
    private static final String ALL_CONSENTS = """
            {"consents":[{"type":"PRIVACY_REQUIRED","termsVersion":"2026-10-v1"},
                         {"type":"PHOTO","termsVersion":"2026-10-v1"}]}""";
    private static final String PRIVACY_ONLY = """
            {"consents":[{"type":"PRIVACY_REQUIRED","termsVersion":"2026-10-v1"}]}""";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private long ownerId;
    private String ownerPhone;
    private String link;

    private record Session(long inquiryId, String token) {

        String path(String suffix) {
            return "/api/v1/customer/inquiries/" + inquiryId + suffix;
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        accounts = new TestAccounts(jdbc);
        ownerPhone = accounts.newPhone();
        ownerId = accounts.approvedOwner(ownerPhone);
        link = accounts.linkToken(ownerId);
        jdbc.update("INSERT INTO owner_work_type (owner_id, work_category, duration_minutes, is_active) "
                + "VALUES (?, 'REPAIR', 60, true), (?, 'INSPECTION', 60, true), (?, 'LEAK', 90, false)",
                ownerId, ownerId, ownerId);
    }

    @AfterEach
    void tearDown() {
        accounts.cleanUp();
    }

    @Test
    @DisplayName("접수 링크로 업체 확인 - 대표 연락처가 없으면 사장님 번호, 제공하는 작업만")
    void intakeInfo() throws Exception {
        mockMvc.perform(get(intake("")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.businessName").value("테스트보일러"))
                .andExpect(jsonPath("$.result.contactPhone").value(ownerPhone))
                .andExpect(jsonPath("$.result.workCategories", contains("INSPECTION", "REPAIR")))
                .andExpect(jsonPath("$.result.intakeAvailable").value(true))
                .andExpect(jsonPath("$.result.termsVersion").value(TERMS))
                .andExpect(jsonPath("$.result.consents[0].type").value("PRIVACY_REQUIRED"))
                .andExpect(jsonPath("$.result.consents[0].required").value(true))
                .andExpect(jsonPath("$.result.consents[1].type").value("PHOTO"))
                .andExpect(jsonPath("$.result.consents[1].required").value(false));
    }

    @Test
    @DisplayName("업체 상태별 접수 링크 - 없음·승인 전·탈퇴 404, 설정 중·이용 제한 403, 접수 중지면 조회만")
    void intakeLinkByOwnerState() throws Exception {
        mockMvc.perform(get("/api/v1/public/intake/no-such-link"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INTAKE_LINK_INVALID"));
        // 링크 확인이 동의 확인보다 먼저
        mockMvc.perform(post("/api/v1/public/intake/no-such-link/inquiries")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"consents\":[]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INTAKE_LINK_INVALID"));

        long pending = accounts.owner(accounts.newPhone(), "PENDING", "READY", null);
        long withdrawn = accounts.owner(accounts.newPhone(), "APPROVED", "WITHDRAWN", null);
        for (long other : List.of(pending, withdrawn)) {
            mockMvc.perform(post("/api/v1/public/intake/" + accounts.linkToken(other) + "/inquiries")
                            .contentType(MediaType.APPLICATION_JSON).content(PRIVACY_ONLY))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("INTAKE_LINK_INVALID"));
        }
        long ready = accounts.owner(accounts.newPhone(), "APPROVED", "READY", null);
        long restricted = accounts.owner(accounts.newPhone(), "APPROVED", "RESTRICTED", null);
        for (long other : List.of(ready, restricted)) {
            mockMvc.perform(get("/api/v1/public/intake/" + accounts.linkToken(other)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("OWNER_UNAVAILABLE"));
        }

        jdbc.update("UPDATE owner SET intake_paused = true WHERE id = ?", ownerId);
        mockMvc.perform(get(intake("")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.intakeAvailable").value(false));
        startRequest(PRIVACY_ONLY)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INTAKE_PAUSED"))
                .andExpect(jsonPath("$.result.reservationLookupAvailable").value(true));
        assertThat(inquiryCount()).isZero();
    }

    @Test
    @DisplayName("필수 동의가 없거나 이전 약관 버전이면 403 CONSENT_REQUIRED, 문의를 만들지 않음")
    void startRequiresCurrentPrivacyConsent() throws Exception {
        startRequest("""
                {"consents":[{"type":"PHOTO","termsVersion":"2026-10-v1"}]}""")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"))
                .andExpect(jsonPath("$.result.requiredConsents[0].type").value("PRIVACY_REQUIRED"))
                .andExpect(jsonPath("$.result.requiredConsents[0].termsVersion").value(TERMS));
        startRequest("""
                {"consents":[{"type":"PRIVACY_REQUIRED","termsVersion":"2026-09-v0"}]}""")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        startRequest("""
                {"consents":[{"type":"PRIVACY_REQUIRED","termsVersion":"2026-10-v1"},
                             {"type":"PRIVACY_REQUIRED","termsVersion":"2026-10-v1"}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        startRequest("{\"consents\":[]}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        assertThat(inquiryCount()).isZero();
    }

    @Test
    @DisplayName("상담 시작 - 동의 기록·문의 생성·24시간 문의 토큰, 토큰으로 내 문의 조회")
    void startInquiry() throws Exception {
        String body = startRequest(ALL_CONSENTS)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.inquiryToken", startsWith("inq_")))
                .andReturn().getResponse().getContentAsString();
        long inquiryId = ((Number) JsonPath.read(body, "$.result.inquiryId")).longValue();
        String token = JsonPath.read(body, "$.result.inquiryToken");
        OffsetDateTime expiresAt = OffsetDateTime.parse(JsonPath.read(body, "$.result.expiresAt"));
        assertThat(expiresAt.getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
        assertThat(Duration.between(OffsetDateTime.now(), expiresAt)).isBetween(Duration.ofHours(23).plusMinutes(59),
                Duration.ofHours(24));

        assertThat(jdbc.queryForList("SELECT consent_type FROM consent WHERE inquiry_id = ? AND terms_version = ? "
                + "ORDER BY id", String.class, inquiryId, TERMS)).containsExactly("PRIVACY_REQUIRED", "PHOTO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_token WHERE inquiry_id = ? AND token_type = 'INQUIRY' "
                + "AND token_hash = ?", Integer.class, inquiryId, TestAccounts.sha256(token))).isEqualTo(1);

        Session session = new Session(inquiryId, token);
        getAs(session.token(), session.path(""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.inquiryId").value(inquiryId))
                .andExpect(jsonPath("$.result.status").value("COLLECTING"))
                .andExpect(jsonPath("$.result.handlerMode").value("AI"))
                .andExpect(jsonPath("$.result.nextAction").value(nullValue()))
                .andExpect(jsonPath("$.result.version").value(0))
                .andExpect(jsonPath("$.result.visitInfo.phone").value(nullValue()))
                .andExpect(jsonPath("$.result.phoneVerified").value(false))
                .andExpect(jsonPath("$.result.consents", contains("PRIVACY_REQUIRED", "PHOTO")))
                .andExpect(jsonPath("$.result.reservation").value(nullValue()));
    }

    @Test
    @DisplayName("문의 토큰은 그 문의에만 - 다른 문의 404, 삭제된 문의 404, 다른 종류 토큰 403, 토큰 없음 401")
    void inquiryTokenScope() throws Exception {
        Session mine = startInquiry(ALL_CONSENTS);
        Session other = startInquiry(ALL_CONSENTS);

        for (String suffix : List.of("", "/messages", "/fields")) {
            getAs(mine.token(), other.path(suffix))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
        }
        postAs(mine.token(), other.path("/messages"), message("m-1", "안녕하세요"))
                .andExpect(status().isNotFound());
        putAs(mine.token(), other.path("/visit-info"), visitInfo(0, "홍길동", "010-1111-2222", "REPAIR"))
                .andExpect(status().isNotFound());
        patchAs(mine.token(), other.path("/fields"), fields(0, "{\"fieldKey\":\"model\",\"value\":\"A\"}"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message WHERE inquiry_id = ?", Integer.class,
                other.inquiryId())).isZero();
        assertThat(jdbc.queryForObject("SELECT customer_id IS NULL FROM inquiry WHERE id = ?", Boolean.class,
                other.inquiryId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inquiry_field WHERE inquiry_id = ?", Integer.class,
                other.inquiryId())).isZero();

        getAs(accounts.ownerToken(ownerId), mine.path(""))
                .andExpect(status().isForbidden());
        String customerToken = accounts.token("CUSTOMER", ownerId, null, "01011112222", "INQUIRY_ACCESS",
                "CURRENT_TIMESTAMP + interval '1 day'");
        getAs(customerToken, mine.path(""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get(mine.path("")))
                .andExpect(status().isUnauthorized());

        jdbc.update("UPDATE inquiry SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", mine.inquiryId());
        getAs(mine.token(), mine.path(""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
    }

    @Test
    @DisplayName("메시지 보내기 - 같은 clientMsgId 재전송은 200 + 처음 메시지, AI 입력 순번은 한 번만")
    void sendMessageIsIdempotent() throws Exception {
        Session session = startInquiry(ALL_CONSENTS);

        String body = postAs(session.token(), session.path("/messages"), message("c-1", "화면에 03이 떠요"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.deliveryStatus").value("SENT"))
                .andExpect(jsonPath("$.result.aiStatus").value("PROCESSING"))
                .andReturn().getResponse().getContentAsString();
        long messageId = ((Number) JsonPath.read(body, "$.result.messageId")).longValue();

        postAs(session.token(), session.path("/messages"), message("c-1", "다른 내용"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.messageId").value(messageId));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message WHERE inquiry_id = ?", Integer.class,
                session.inquiryId())).isEqualTo(1);
        assertThat(aiInputSeq(session)).isEqualTo(1);

        getAs(session.token(), session.path("/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.messages", hasSize(1)))
                .andExpect(jsonPath("$.result.messages[0].sender").value("CUSTOMER"))
                .andExpect(jsonPath("$.result.messages[0].content").value("화면에 03이 떠요"))
                .andExpect(jsonPath("$.result.messages[0].photoIds", hasSize(0)))
                .andExpect(jsonPath("$.result.hasMore").value(false));
        getAs(session.token(), session.path("/messages?afterId=" + messageId))
                .andExpect(jsonPath("$.result.messages", hasSize(0)));

        // 사장님 응대 중이면 AI가 답하지 않음
        jdbc.update("UPDATE inquiry SET handler_mode = 'OWNER' WHERE id = ?", session.inquiryId());
        postAs(session.token(), session.path("/messages"), message("c-2", "사장님께 여쭤요"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.aiStatus").value("NONE"));
    }

    @Test
    @DisplayName("같은 clientMsgId가 동시에 와도 메시지는 하나 (문의 행 잠금)")
    void concurrentResendSavesOnce() throws Exception {
        Session session = startInquiry(ALL_CONSENTS);
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Callable<Integer> send = () -> {
                    ready.countDown();
                    ready.await();
                    return postAs(session.token(), session.path("/messages"), message("same", "동시에"))
                            .andReturn().getResponse().getStatus();
                };
                results.add(executor.submit(send));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsOnly(200, 201).filteredOn(code -> code == 201).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message WHERE inquiry_id = ?", Integer.class,
                session.inquiryId())).isEqualTo(1);
        assertThat(aiInputSeq(session)).isEqualTo(1);
    }

    @Test
    @DisplayName("메시지 입력 검사 - 내용·사진 둘 다 없음 400, 빈 문자열 400, 사진 동의 없이 사진 403")
    void sendMessageValidation() throws Exception {
        Session session = startInquiry(PRIVACY_ONLY);

        postAs(session.token(), session.path("/messages"), "{\"clientMsgId\":\"v-1\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("content"));
        postAs(session.token(), session.path("/messages"), message("v-2", "  "))
                .andExpect(status().isBadRequest());
        postAs(session.token(), session.path("/messages"), "{\"content\":\"아이디 없음\"}")
                .andExpect(status().isBadRequest());

        long photoId = donePhoto(session);
        postAs(session.token(), session.path("/messages"), photoMessage("v-3", photoId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"))
                .andExpect(jsonPath("$.result.requiredConsents[0].type").value("PHOTO"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message WHERE inquiry_id = ?", Integer.class,
                session.inquiryId())).isZero();
        assertThat(aiInputSeq(session)).isZero();

        // 필수 동의 기록이 없는 문의(다른 경로로 생긴 문의 등)에는 대화·방문정보를 저장하지 않음
        jdbc.update("DELETE FROM consent WHERE inquiry_id = ?", session.inquiryId());
        postAs(session.token(), session.path("/messages"), message("v-4", "안녕하세요"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"))
                .andExpect(jsonPath("$.result.requiredConsents[0].type").value("PRIVACY_REQUIRED"));
        putAs(session.token(), session.path("/visit-info"), visitInfo(0, "홍길동", "01011112222", null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer WHERE owner_id = ?", Integer.class, ownerId))
                .isZero();
    }

    @Test
    @DisplayName("사진은 이 상담에서 업로드를 마친, 아직 보내지 않은 것만 메시지에 연결")
    void sendMessageWithPhotos() throws Exception {
        Session session = startInquiry(ALL_CONSENTS);
        Session other = startInquiry(ALL_CONSENTS);
        long photoId = donePhoto(session);
        long othersPhoto = donePhoto(other);
        long uploading = jdbc.queryForObject("INSERT INTO photo (inquiry_id, s3_key_original, mime_type, size_bytes) "
                + "VALUES (?, 'k/uploading.jpg', 'image/jpeg', 100) RETURNING id", Long.class, session.inquiryId());

        for (long notMine : List.of(othersPhoto, uploading, 999_999_999L)) {
            postAs(session.token(), session.path("/messages"), photoMessage("p-" + notMine, notMine))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.result[0].field").value("photoIds"));
        }

        postAs(session.token(), session.path("/messages"), photoMessage("p-ok", photoId))
                .andExpect(status().isCreated());
        getAs(session.token(), session.path("/messages"))
                .andExpect(jsonPath("$.result.messages[0].content").value(nullValue()))
                .andExpect(jsonPath("$.result.messages[0].photoIds", contains((int) photoId)));
        // 이미 보낸 사진
        postAs(session.token(), session.path("/messages"), photoMessage("p-again", photoId))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("종료된 상담은 쓰기 409 (재전송은 처음 결과), 상담을 받을 수 없게 된 업체는 쓰기 403")
    void closedInquiryAndUnavailableOwner() throws Exception {
        Session session = startInquiry(ALL_CONSENTS);
        postAs(session.token(), session.path("/messages"), message("before-close", "안녕하세요"))
                .andExpect(status().isCreated());

        jdbc.update("UPDATE inquiry SET status = 'CLOSED' WHERE id = ?", session.inquiryId());
        postAs(session.token(), session.path("/messages"), message("after-close", "계세요?"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INQUIRY_CLOSED"));
        postAs(session.token(), session.path("/messages"), message("before-close", "안녕하세요"))
                .andExpect(status().isOk());
        putAs(session.token(), session.path("/visit-info"), visitInfo(0, "홍길동", "01011112222", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INQUIRY_CLOSED"));
        patchAs(session.token(), session.path("/fields"), fields(0, "{\"fieldKey\":\"model\",\"value\":\"A\"}"))
                .andExpect(status().isConflict());

        Session another = startInquiry(ALL_CONSENTS);
        jdbc.update("UPDATE owner SET status = 'RESTRICTED' WHERE id = ?", ownerId);
        postAs(another.token(), another.path("/messages"), message("r-1", "안녕하세요"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWNER_UNAVAILABLE"));
        getAs(another.token(), another.path(""))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("방문정보 저장 - 번호 정규화·고객 연결, version 1씩, 이전 version이면 409, 다른 번호면 인증 초기화")
    void visitInfo() throws Exception {
        Session session = startInquiry(ALL_CONSENTS);

        putAs(session.token(), session.path("/visit-info"), visitInfo(0, " 홍길동 ", "010-1111-2222", "REPAIR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value(1))
                .andExpect(jsonPath("$.result.visitInfo.name").value("홍길동"))
                .andExpect(jsonPath("$.result.visitInfo.phone").value("01011112222"))
                .andExpect(jsonPath("$.result.visitInfo.address").value("경기 고양시 덕양구 화정로 1"))
                .andExpect(jsonPath("$.result.visitInfo.addressDetail").value("101동 202호"))
                .andExpect(jsonPath("$.result.visitInfo.workCategory").value("REPAIR"))
                .andExpect(jsonPath("$.result.phoneVerified").value(false));
        assertThat(aiInputSeq(session)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name FROM customer WHERE owner_id = ? AND phone = '01011112222'",
                String.class, ownerId)).isEqualTo("홍길동");

        putAs(session.token(), session.path("/visit-info"), visitInfo(0, "홍길동", "01011112222", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INQUIRY_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.result.currentVersion").value(1));

        // 이름만 바뀌어도(문의 칸은 그대로) version은 1 오름, 작업 유형을 안 보내면 그대로
        jdbc.update("UPDATE inquiry SET phone_verified_at = CURRENT_TIMESTAMP WHERE id = ?", session.inquiryId());
        putAs(session.token(), session.path("/visit-info"), visitInfo(1, "홍길순", "01011112222", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value(2))
                .andExpect(jsonPath("$.result.visitInfo.name").value("홍길순"))
                .andExpect(jsonPath("$.result.visitInfo.workCategory").value("REPAIR"))
                .andExpect(jsonPath("$.result.phoneVerified").value(true));

        putAs(session.token(), session.path("/visit-info"), visitInfo(2, "홍길순", "01033334444", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value(3))
                .andExpect(jsonPath("$.result.phoneVerified").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer WHERE owner_id = ?", Integer.class, ownerId))
                .isEqualTo(2);
        assertThat(aiInputSeq(session)).isEqualTo(3);
    }

    @Test
    @DisplayName("방문정보 입력 검사 - 휴대전화 아님·제공 안 하는 작업·좌표 없음 400, 저장하지 않음")
    void visitInfoValidation() throws Exception {
        Session session = startInquiry(ALL_CONSENTS);

        putAs(session.token(), session.path("/visit-info"), visitInfo(0, "홍길동", "02-123-4567", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("phone"));
        putAs(session.token(), session.path("/visit-info"), visitInfo(0, "홍길동", "01011112222", "LEAK"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("workCategory"));
        putAs(session.token(), session.path("/visit-info"), """
                {"version":0,"name":"홍길동","phone":"01011112222","address":"경기 고양시 덕양구 화정로 1"}""")
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT customer_id IS NULL FROM inquiry WHERE id = ?", Boolean.class,
                session.inquiryId())).isTrue();
        assertThat(aiInputSeq(session)).isZero();
    }

    @Test
    @DisplayName("같은 번호의 문의는 같은 고객으로 이어지지만, 번호 인증 전에는 기존 고객 이름을 바꾸거나 읽지 못함")
    void sameCustomerAcrossInquiries() throws Exception {
        Session first = startInquiry(ALL_CONSENTS);
        Session second = startInquiry(ALL_CONSENTS);
        putAs(first.token(), first.path("/visit-info"), visitInfo(0, "홍길동", "01011112222", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.visitInfo.name").value("홍길동"));

        // 남이 같은 번호를 넣어도 기존 고객 이름은 그대로, 응답에도 기존 이름이 나오지 않음
        putAs(second.token(), second.path("/visit-info"), visitInfo(0, "김철수", "010-1111-2222", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.visitInfo.name").value(nullValue()))
                .andExpect(jsonPath("$.result.visitInfo.phone").value("01011112222"));
        getAs(second.token(), second.path(""))
                .andExpect(jsonPath("$.result.visitInfo.name").value(nullValue()));
        assertThat(jdbc.queryForList("SELECT DISTINCT customer_id FROM inquiry WHERE owner_id = ?", Long.class,
                ownerId)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT name FROM customer WHERE owner_id = ?", String.class, ownerId))
                .isEqualTo("홍길동");
        // 같은 고객을 다른 문의도 쓰게 됐으므로 인증 전에는 처음 문의에도 이름을 숨김
        getAs(first.token(), first.path(""))
                .andExpect(jsonPath("$.result.visitInfo.name").value(nullValue()));

        // 이 번호로 인증한 문의는 이름을 보고 바꿀 수 있음
        jdbc.update("UPDATE inquiry SET phone_verified_at = CURRENT_TIMESTAMP WHERE id = ?", second.inquiryId());
        putAs(second.token(), second.path("/visit-info"), visitInfo(1, "홍길동(모친)", "01011112222", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.visitInfo.name").value("홍길동(모친)"))
                .andExpect(jsonPath("$.result.phoneVerified").value(true));
        assertThat(jdbc.queryForObject("SELECT name FROM customer WHERE owner_id = ?", String.class, ownerId))
                .isEqualTo("홍길동(모친)");
    }

    @Test
    @DisplayName("다른 업체에 같은 번호로 접수하면 고객은 업체마다 따로")
    void customersAreSeparatedByOwner() throws Exception {
        Session mine = startInquiry(ALL_CONSENTS);
        long otherOwner = accounts.approvedOwner(accounts.newPhone());
        String otherBody = mockMvc.perform(post("/api/v1/public/intake/" + accounts.linkToken(otherOwner)
                        + "/inquiries").contentType(MediaType.APPLICATION_JSON).content(PRIVACY_ONLY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Session theirs = new Session(((Number) JsonPath.read(otherBody, "$.result.inquiryId")).longValue(),
                JsonPath.read(otherBody, "$.result.inquiryToken"));

        putAs(mine.token(), mine.path("/visit-info"), visitInfo(0, "홍길동", "01011112222", null))
                .andExpect(status().isOk());
        putAs(theirs.token(), theirs.path("/visit-info"), visitInfo(0, "김철수", "01011112222", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.visitInfo.name").value("김철수"));

        assertThat(jdbc.queryForObject("SELECT name FROM customer WHERE owner_id = ? AND phone = '01011112222'",
                String.class, ownerId)).isEqualTo("홍길동");
        assertThat(jdbc.queryForObject("SELECT name FROM customer WHERE owner_id = ? AND phone = '01011112222'",
                String.class, otherOwner)).isEqualTo("김철수");
        // 다른 업체 문의 토큰으로는 이 업체 문의에 닿지 않음
        getAs(theirs.token(), mine.path(""))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("진행 중인 예약이 있으면 주소·작업 변경은 422 RESERVATION_CHANGE_REQUIRED, 이름·출입 안내는 저장")
    void visitInfoWithActiveReservation() throws Exception {
        Session session = startInquiry(ALL_CONSENTS);
        putAs(session.token(), session.path("/visit-info"), visitInfo(0, "홍길동", "01011112222", "REPAIR"))
                .andExpect(status().isOk());
        long reservationId = jdbc.queryForObject("""
                INSERT INTO reservation (inquiry_id, owner_id, customer_id, start_at, end_at, address, lat, lng,
                                         status, work_type_id)
                SELECT i.id, i.owner_id, i.customer_id, now() + interval '1 day', now() + interval '1 day 1 hour',
                       i.address, i.lat, i.lng, 'CONFIRMED', i.work_type_id
                FROM inquiry i WHERE i.id = ? RETURNING id""", Long.class, session.inquiryId());

        putAs(session.token(), session.path("/visit-info"), visitInfo(1, "홍길동", "01011112222", "REPAIR")
                .replace("화정로 1", "화정로 2"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RESERVATION_CHANGE_REQUIRED"))
                .andExpect(jsonPath("$.result.reservationId").value(reservationId));
        putAs(session.token(), session.path("/visit-info"), visitInfo(1, "홍길동", "01011112222", "INSPECTION"))
                .andExpect(status().isUnprocessableContent());
        // 예약은 원래 고객에 묶여 있으므로 번호 변경도 예약 변경 절차로
        putAs(session.token(), session.path("/visit-info"), visitInfo(1, "홍길동", "01099998888", null))
                .andExpect(status().isUnprocessableContent());

        // 좌표 자릿수만 다르거나 7자리 넘게 와도 같은 위치는 변경이 아님 (DB는 소수 7자리)
        putAs(session.token(), session.path("/visit-info"), visitInfo(1, "홍길순", "01011112222", null)
                .replace("37.634", "37.63400000004"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.visitInfo.name").value("홍길순"))
                .andExpect(jsonPath("$.result.visitInfo.lat").value(37.634))
                .andExpect(jsonPath("$.result.reservation.reservationId").value(reservationId))
                .andExpect(jsonPath("$.result.reservation.status").value("CONFIRMED"));

        // 예약 쪽 주소가 따로 바뀐 뒤(예약 변경)에도 문의 값을 그대로 두고 출입 안내만 고치면 저장
        jdbc.update("UPDATE reservation SET address = '경기 고양시 덕양구 화정로 9' WHERE id = ?", reservationId);
        putAs(session.token(), session.path("/visit-info"), visitInfo(2, "홍길순", "01011112222", null)
                .replace("주차는 지하 2층", "주차는 지상"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.visitInfo.accessNote").value("주차는 지상"));
    }

    @Test
    @DisplayName("접수정보 수정 - 출처 CUSTOMER로 새 행·이전 값은 이력, 모름은 미확인, 그대로면 version 유지")
    void updateFields() throws Exception {
        Session session = startInquiry(ALL_CONSENTS);

        patchAs(session.token(), session.path("/fields"), fields(0,
                "{\"fieldKey\":\"manufacturer\",\"value\":\" 린나이 \"}",
                "{\"fieldKey\":\"error_code\",\"unsure\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value(1))
                .andExpect(jsonPath("$.result.fields", hasSize(2)))
                .andExpect(jsonPath("$.result.fields[0].fieldKey").value("manufacturer"))
                .andExpect(jsonPath("$.result.fields[0].value").value("린나이"))
                .andExpect(jsonPath("$.result.fields[0].source").value("CUSTOMER"))
                .andExpect(jsonPath("$.result.fields[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.result.fields[1].fieldKey").value("error_code"))
                .andExpect(jsonPath("$.result.fields[1].value").value(nullValue()))
                .andExpect(jsonPath("$.result.fields[1].status").value("UNCONFIRMED"))
                .andExpect(jsonPath("$.result.fields[1].unconfirmedReason").value("CUSTOMER_UNSURE"));
        assertThat(jdbc.queryForObject("SELECT manufacturer FROM inquiry WHERE id = ?", String.class,
                session.inquiryId())).isEqualTo("린나이");
        assertThat(aiInputSeq(session)).isEqualTo(1);

        // 같은 내용 다시 → 새 행·version·AI 입력 순번 그대로
        patchAs(session.token(), session.path("/fields"), fields(1, "{\"fieldKey\":\"manufacturer\",\"value\":\"린나이\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value(1));
        assertThat(aiInputSeq(session)).isEqualTo(1);

        patchAs(session.token(), session.path("/fields"), fields(1, "{\"fieldKey\":\"manufacturer\",\"value\":\"경동\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value(2))
                .andExpect(jsonPath("$.result.fields[1].value").value("경동"));
        assertThat(jdbc.queryForList("SELECT field_value FROM inquiry_field WHERE inquiry_id = ? "
                + "AND field_key = 'manufacturer' ORDER BY id", String.class, session.inquiryId()))
                .containsExactly("린나이", "경동");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inquiry_field WHERE inquiry_id = ? AND is_current",
                Integer.class, session.inquiryId())).isEqualTo(2);

        patchAs(session.token(), session.path("/fields"), fields(1, "{\"fieldKey\":\"model\",\"value\":\"A\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INQUIRY_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.result.currentVersion").value(2));

        getAs(session.token(), session.path("/fields"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value(2))
                .andExpect(jsonPath("$.result.fields", hasSize(2)));
    }

    @Test
    @DisplayName("사장님이 정한 값은 '모름'·같은 값으로 덮지 않고, 다르게 고치면 미확인(CONFLICT)으로 저장")
    void customerEditAgainstOwnerValue() throws Exception {
        Session session = startInquiry(ALL_CONSENTS);
        jdbc.update("INSERT INTO inquiry_field (inquiry_id, field_key, field_value, source, status) "
                + "VALUES (?, 'model', 'NCB-500', 'OWNER', 'CONFIRMED')", session.inquiryId());
        jdbc.update("UPDATE inquiry SET model = 'NCB-500' WHERE id = ?", session.inquiryId());

        patchAs(session.token(), session.path("/fields"), fields(0, "{\"fieldKey\":\"model\",\"value\":\"NCB-500\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value(0))
                .andExpect(jsonPath("$.result.fields[0].source").value("OWNER"));
        patchAs(session.token(), session.path("/fields"), fields(0, "{\"fieldKey\":\"model\",\"unsure\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value(0))
                .andExpect(jsonPath("$.result.fields[0].value").value("NCB-500"))
                .andExpect(jsonPath("$.result.fields[0].source").value("OWNER"));
        assertThat(jdbc.queryForObject("SELECT model FROM inquiry WHERE id = ?", String.class,
                session.inquiryId())).isEqualTo("NCB-500");
        patchAs(session.token(), session.path("/fields"), fields(0, "{\"fieldKey\":\"model\",\"value\":\"NCB-700\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.fields[0].value").value("NCB-700"))
                .andExpect(jsonPath("$.result.fields[0].source").value("CUSTOMER"))
                .andExpect(jsonPath("$.result.fields[0].status").value("UNCONFIRMED"))
                .andExpect(jsonPath("$.result.fields[0].unconfirmedReason").value("CONFLICT"));
    }

    @Test
    @DisplayName("접수정보 입력 검사 - 모르는 항목·같은 항목 두 번·칸 길이 초과·값도 unsure도 없음은 칸별 400")
    void updateFieldsValidation() throws Exception {
        Session session = startInquiry(ALL_CONSENTS);

        patchAs(session.token(), session.path("/fields"), fields(0,
                "{\"fieldKey\":\"address\",\"value\":\"서울\"}",
                "{\"fieldKey\":\"model\",\"value\":\"A\"}",
                "{\"fieldKey\":\"model\",\"value\":\"B\"}",
                "{\"fieldKey\":\"error_code\",\"value\":\"" + "E".repeat(21) + "\"}",
                "{\"fieldKey\":\"symptom\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result", hasSize(4)))
                .andExpect(jsonPath("$.result[0].field").value("fields[0].fieldKey"))
                .andExpect(jsonPath("$.result[1].field").value("fields[2].fieldKey"))
                .andExpect(jsonPath("$.result[2].field").value("fields[3].value"))
                .andExpect(jsonPath("$.result[3].field").value("fields[4].value"));
        patchAs(session.token(), session.path("/fields"), "{\"version\":0,\"fields\":[]}")
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inquiry_field WHERE inquiry_id = ?", Integer.class,
                session.inquiryId())).isZero();
    }

    @Test
    @DisplayName("다른 요청이 문의 행을 잠그고 있으면 3초 뒤 503 + Retry-After")
    void writeWaitsForInquiryLock() throws Exception {
        Session session = startInquiry(ALL_CONSENTS);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM inquiry WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, session.inquiryId());
                lock.executeQuery();
            }
            try {
                postAs(session.token(), session.path("/messages"), message("locked", "안녕하세요"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                        .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"));
            } finally {
                holder.rollback();
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message WHERE inquiry_id = ?", Integer.class,
                session.inquiryId())).isZero();
    }

    private String intake(String suffix) {
        return "/api/v1/public/intake/" + link + suffix;
    }

    private ResultActions startRequest(String json) throws Exception {
        return mockMvc.perform(post(intake("/inquiries")).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private Session startInquiry(String consents) throws Exception {
        String body = startRequest(consents).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Session(((Number) JsonPath.read(body, "$.result.inquiryId")).longValue(),
                JsonPath.read(body, "$.result.inquiryToken"));
    }

    private long donePhoto(Session session) {
        return jdbc.queryForObject("INSERT INTO photo (inquiry_id, s3_key_original, mime_type, size_bytes, "
                + "upload_status) VALUES (?, 'k/done.jpg', 'image/jpeg', 100, 'DONE') RETURNING id", Long.class,
                session.inquiryId());
    }

    private int inquiryCount() {
        return jdbc.queryForObject("SELECT count(*) FROM inquiry WHERE owner_id = ?", Integer.class, ownerId);
    }

    private long aiInputSeq(Session session) {
        return jdbc.queryForObject("SELECT ai_input_seq FROM inquiry WHERE id = ?", Long.class, session.inquiryId());
    }

    private static String message(String clientMsgId, String content) {
        return "{\"clientMsgId\":\"" + clientMsgId + "\",\"content\":\"" + content + "\"}";
    }

    private static String photoMessage(String clientMsgId, long photoId) {
        return "{\"clientMsgId\":\"" + clientMsgId + "\",\"photoIds\":[" + photoId + "]}";
    }

    private static String visitInfo(int version, String name, String phone, String workCategory) {
        return """
                {"version":%d,"name":"%s","phone":"%s","address":"경기 고양시 덕양구 화정로 1",
                 "addressDetail":"101동 202호","lat":37.634,"lng":126.832,"accessNote":"주차는 지하 2층",
                 "workCategory":%s}""".formatted(version, name, phone,
                workCategory == null ? "null" : "\"" + workCategory + "\"");
    }

    private static String fields(int version, String... items) {
        return "{\"version\":" + version + ",\"fields\":[" + String.join(",", items) + "]}";
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
