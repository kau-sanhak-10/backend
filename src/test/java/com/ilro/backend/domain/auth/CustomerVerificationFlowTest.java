package com.ilro.backend.domain.auth;

import com.ilro.backend.support.CapturingSmsSender;
import com.ilro.backend.support.TestAccounts;
import com.ilro.backend.support.TestSmsConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 고객 휴대전화 인증·상담 복구 (BE-18, 명세 4.1·5.1·7.2)
// 예약 전 번호 확인(CUSTOMER_VERIFY)은 문의 토큰으로, 상담 복구·예약 조회 인증은 접수 링크로 받음
// 인증 확인과 문의 기록이 서로 다른 트랜잭션 결과를 봐야 해서 테스트 트랜잭션 없이 실제로 저장하고 지움
@SpringBootTest
@Import(TestSmsConfig.class)
class CustomerVerificationFlowTest {

    private static final String VERIFICATIONS = "/api/v1/auth/verifications";
    private static final String RECOVERY = "/api/v1/customer/inquiries";
    private static final String PRIVACY_ONLY = """
            {"consents":[{"type":"PRIVACY_REQUIRED","termsVersion":"2026-10-v1"}]}""";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CapturingSmsSender smsSender;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private String ip;
    private long ownerId;
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
        ip = TestAccounts.newIp();
        ownerId = accounts.approvedOwner(accounts.newPhone());
        link = accounts.linkToken(ownerId);
    }

    @AfterEach
    void tearDown() {
        accounts.cleanUp();
    }

    // ---- 예약 전 번호 확인 (CUSTOMER_VERIFY) ----

    @Test
    @DisplayName("예약 전 번호 확인 - 방문정보 번호로 받아 확인하면 그 문의에 인증 시각이 남고 버전이 오름")
    void customerVerifyFlow() throws Exception {
        String phone = accounts.newPhone();
        Session session = startWithVisitInfo(link, phone);
        int versionBefore = version(session);
        String hyphenated = phone.substring(0, 3) + "-" + phone.substring(3, 7) + "-" + phone.substring(7);

        long verificationId = sendOk(session.token(), "CUSTOMER_VERIFY", hyphenated, null);
        assertThat(jdbc.queryForObject("SELECT target_inquiry_id FROM phone_verification WHERE id = ?", Long.class,
                verificationId)).isEqualTo(session.inquiryId());

        confirm(verificationId, smsSender.lastCode(phone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.purpose").value("CUSTOMER_VERIFY"))
                .andExpect(jsonPath("$.result.inquiryId").value(session.inquiryId()))
                .andExpect(jsonPath("$.result.verifiedAt").value(endsWith("+09:00")))
                .andExpect(jsonPath("$.result.accessToken").doesNotExist());

        assertThat(jdbc.queryForObject("SELECT phone_verified_at IS NOT NULL FROM inquiry WHERE id = ?",
                Boolean.class, session.inquiryId())).isTrue();
        assertThat(version(session)).isEqualTo(versionBefore + 1);
        getAs(session.token(), session.path(""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.phoneVerified").value(true));
        // 고객 인증으로는 어떤 토큰도 새로 생기지 않음
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_token WHERE token_type = 'CUSTOMER' "
                + "AND customer_phone = ?", Integer.class, phone)).isZero();
    }

    @Test
    @DisplayName("예약 전 번호 확인 거절 - 토큰 없음 401, 사장님 토큰 403, 방문정보와 다른 번호·방문정보 전 400, 종료 409 (문자 없음)")
    void customerVerifyRejects() throws Exception {
        String phone = accounts.newPhone();
        Session session = startWithVisitInfo(link, phone);
        String other = accounts.newPhone();
        Session noVisitInfo = start(link);

        sendCode(null, "CUSTOMER_VERIFY", phone, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        sendCode(accounts.ownerToken(ownerId), "CUSTOMER_VERIFY", phone, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        sendCode(session.token(), "CUSTOMER_VERIFY", other, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result[0].field").value("phone"));
        sendCode(noVisitInfo.token(), "CUSTOMER_VERIFY", phone, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("phone"));
        jdbc.update("UPDATE inquiry SET status = 'CLOSED' WHERE id = ?", session.inquiryId());
        sendCode(session.token(), "CUSTOMER_VERIFY", phone, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INQUIRY_CLOSED"));

        assertThat(smsSender.hasSentTo(phone)).isFalse();
        assertThat(smsSender.hasSentTo(other)).isFalse();
    }

    @Test
    @DisplayName("보낸 뒤 방문정보 번호가 바뀌면 확인 403 - 인증은 쓰이지 않아 번호를 되돌리면 같은 번호로 확인됨")
    void phoneChangedBeforeConfirm() throws Exception {
        String phone = accounts.newPhone();
        Session session = startWithVisitInfo(link, phone);
        long verificationId = sendOk(session.token(), "CUSTOMER_VERIFY", phone, null);
        String code = smsSender.lastCode(phone);

        putAs(session.token(), session.path("/visit-info"), visitInfo(version(session), accounts.newPhone()))
                .andExpect(status().isOk());
        confirm(verificationId, code)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("VERIFICATION_PURPOSE_MISMATCH"));
        assertThat(jdbc.queryForObject("SELECT verified_at IS NULL FROM phone_verification WHERE id = ?",
                Boolean.class, verificationId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT phone_verified_at IS NULL FROM inquiry WHERE id = ?",
                Boolean.class, session.inquiryId())).isTrue();

        putAs(session.token(), session.path("/visit-info"), visitInfo(version(session), phone))
                .andExpect(status().isOk());
        confirm(verificationId, code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.inquiryId").value(session.inquiryId()));
    }

    @Test
    @DisplayName("인증한 뒤 방문정보 번호를 바꾸면 인증이 풀림")
    void phoneChangeClearsVerification() throws Exception {
        String phone = accounts.newPhone();
        Session session = startWithVisitInfo(link, phone);
        long verificationId = sendOk(session.token(), "CUSTOMER_VERIFY", phone, null);
        confirm(verificationId, smsSender.lastCode(phone)).andExpect(status().isOk());

        putAs(session.token(), session.path("/visit-info"), visitInfo(version(session), accounts.newPhone()))
                .andExpect(status().isOk());
        getAs(session.token(), session.path(""))
                .andExpect(jsonPath("$.result.phoneVerified").value(false));
    }

    // ---- 기존 상담 이어가기 (INQUIRY_ACCESS) ----

    @Test
    @DisplayName("상담 복구 - 링크와 번호로 인증 → 고객 토큰 → 이 업체·이 번호의 문의만 → 고른 문의의 새 문의 토큰")
    void inquiryAccessFlow() throws Exception {
        String phone = accounts.newPhone();
        Session first = startWithVisitInfo(link, phone);
        Session closed = startWithVisitInfo(link, phone);
        Session latest = startWithVisitInfo(link, phone);
        Session deleted = startWithVisitInfo(link, phone);
        Session otherPhone = startWithVisitInfo(link, accounts.newPhone());
        long otherOwnerId = accounts.approvedOwner(accounts.newPhone());
        Session otherOwner = startWithVisitInfo(accounts.linkToken(otherOwnerId), phone);
        jdbc.update("UPDATE inquiry SET status = 'CLOSED' WHERE id = ?", closed.inquiryId());
        jdbc.update("UPDATE inquiry SET deleted_at = now() WHERE id = ?", deleted.inquiryId());

        String customerToken = recoveryToken(phone);
        assertThat(jdbc.queryForMap("SELECT token_type, owner_id, customer_phone, scope, inquiry_id FROM auth_token "
                + "WHERE token_hash = ?", TestAccounts.sha256(customerToken)))
                .containsAllEntriesOf(Map.of("token_type", "CUSTOMER", "owner_id", ownerId, "customer_phone", phone,
                        "scope", "INQUIRY_ACCESS"))
                .containsEntry("inquiry_id", null);

        getAs(customerToken, RECOVERY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.inquiries", hasSize(3)))
                .andExpect(jsonPath("$.result.inquiries[*].inquiryId", contains((int) latest.inquiryId(),
                        (int) closed.inquiryId(), (int) first.inquiryId())))
                .andExpect(jsonPath("$.result.inquiries[1].status").value("CLOSED"))
                .andExpect(jsonPath("$.result.inquiries[0].createdAt").value(endsWith("+09:00")));

        String body = postAs(customerToken, "/api/v1/customer/inquiries/" + first.inquiryId() + "/access-token")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.inquiryId").value(first.inquiryId()))
                .andExpect(jsonPath("$.result.inquiryToken").value(startsWith("inq_")))
                .andExpect(jsonPath("$.result.expiresAt").value(endsWith("+09:00")))
                .andReturn().getResponse().getContentAsString();
        String recovered = JsonPath.read(body, "$.result.inquiryToken");
        getAs(recovered, first.path(""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.inquiryId").value(first.inquiryId()));
        // 원래 기기의 문의 토큰도 그대로, 새 토큰은 다른 문의에 못 씀
        getAs(first.token(), first.path("")).andExpect(status().isOk());
        getAs(recovered, latest.path("")).andExpect(status().isNotFound());
        // 고객 토큰으로는 문의 내용을 바로 볼 수 없음
        getAs(customerToken, first.path("")).andExpect(status().isForbidden());

        for (Session hidden : new Session[]{otherPhone, otherOwner, deleted}) {
            postAs(customerToken, "/api/v1/customer/inquiries/" + hidden.inquiryId() + "/access-token")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
        }
        postAs(customerToken, "/api/v1/customer/inquiries/" + closed.inquiryId() + "/access-token")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INQUIRY_CLOSED"));
    }

    @Test
    @DisplayName("상담 기록이 없는 번호도 문자를 받고 목록은 비어 있음 - 번호만으로 상담 여부를 알 수 없게")
    void noInquiriesStillSends() throws Exception {
        String phone = accounts.newPhone();

        String customerToken = recoveryToken(phone);

        getAs(customerToken, RECOVERY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.inquiries", hasSize(0)));
    }

    @Test
    @DisplayName("상담 복구·예약 조회 발송 - 링크 없음 400, 모르는 링크·탈퇴 업체 404 (문자 없음), 이용 제한 업체는 지난 상담을 볼 수 있음")
    void linkRequired() throws Exception {
        String phone = accounts.newPhone();
        long withdrawn = accounts.owner(accounts.newPhone(), "APPROVED", "WITHDRAWN", null);

        sendCode(null, "INQUIRY_ACCESS", phone, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result[0].field").value("linkToken"));
        sendCode(null, "RESERVATION_LOOKUP", phone, " ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("linkToken"));
        sendCode(null, "INQUIRY_ACCESS", phone, "no-such-link")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INTAKE_LINK_INVALID"));
        sendCode(null, "INQUIRY_ACCESS", phone, accounts.linkToken(withdrawn))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INTAKE_LINK_INVALID"));
        assertThat(smsSender.hasSentTo(phone)).isFalse();

        Session session = startWithVisitInfo(link, phone);
        jdbc.update("UPDATE owner SET status = 'RESTRICTED' WHERE id = ?", ownerId);
        getAs(recoveryToken(phone), RECOVERY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.inquiries[0].inquiryId").value(session.inquiryId()));
    }

    @Test
    @DisplayName("보낸 뒤 업체가 탈퇴하면 확인 404 - 고객 토큰이 생기지 않음")
    void ownerWithdrawnBeforeConfirm() throws Exception {
        String phone = accounts.newPhone();
        long verificationId = sendOk(null, "INQUIRY_ACCESS", phone, link);
        jdbc.update("UPDATE owner SET status = 'WITHDRAWN' WHERE id = ?", ownerId);

        confirm(verificationId, smsSender.lastCode(phone))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INTAKE_LINK_INVALID"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_token WHERE owner_id = ? AND token_type = 'CUSTOMER'",
                Integer.class, ownerId)).isZero();
    }

    // ---- 토큰 목적 ----

    @Test
    @DisplayName("예약 조회 인증은 예약 조회 고객 토큰 - 상담 복구 목록에는 403 VERIFICATION_PURPOSE_MISMATCH, 문의 토큰 403")
    void tokenScopes() throws Exception {
        String phone = accounts.newPhone();
        Session session = startWithVisitInfo(link, phone);

        long verificationId = sendOk(null, "RESERVATION_LOOKUP", phone, link);
        String body = confirm(verificationId, smsSender.lastCode(phone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.purpose").value("RESERVATION_LOOKUP"))
                .andExpect(jsonPath("$.result.accessToken").value(startsWith("cus_")))
                .andReturn().getResponse().getContentAsString();
        String lookupToken = JsonPath.read(body, "$.result.accessToken");
        assertThat(jdbc.queryForObject("SELECT scope FROM auth_token WHERE token_hash = ?", String.class,
                TestAccounts.sha256(lookupToken))).isEqualTo("RESERVATION_LOOKUP");

        getAs(lookupToken, RECOVERY)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("VERIFICATION_PURPOSE_MISMATCH"));
        postAs(lookupToken, session.path("/access-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("VERIFICATION_PURPOSE_MISMATCH"));
        getAs(session.token(), RECOVERY)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        postAs(session.token(), session.path("/access-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get(RECOVERY))
                .andExpect(status().isUnauthorized());
    }

    // ---- 도우미 ----

    private Session start(String linkToken) throws Exception {
        String body = mockMvc.perform(post("/api/v1/public/intake/" + linkToken + "/inquiries")
                        .contentType(MediaType.APPLICATION_JSON).content(PRIVACY_ONLY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Session(((Number) JsonPath.read(body, "$.result.inquiryId")).longValue(),
                JsonPath.read(body, "$.result.inquiryToken"));
    }

    private Session startWithVisitInfo(String linkToken, String phone) throws Exception {
        Session session = start(linkToken);
        putAs(session.token(), session.path("/visit-info"), visitInfo(0, phone))
                .andExpect(status().isOk());
        return session;
    }

    // 상담 복구 인증을 끝까지 받아 고객 토큰을 돌려줌
    private String recoveryToken(String phone) throws Exception {
        long verificationId = sendOk(null, "INQUIRY_ACCESS", phone, link);
        String body = confirm(verificationId, smsSender.lastCode(phone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.purpose").value("INQUIRY_ACCESS"))
                .andExpect(jsonPath("$.result.accessToken").value(startsWith("cus_")))
                .andExpect(jsonPath("$.result.expiresAt").value(endsWith("+09:00")))
                .andExpect(jsonPath("$.result.owner").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.result.accessToken");
    }

    private int version(Session session) {
        return jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?", Integer.class, session.inquiryId());
    }

    private static String visitInfo(int version, String phone) {
        return """
                {"version":%d,"name":"홍길동","phone":"%s","address":"경기 고양시 덕양구 화정로 1",
                 "addressDetail":"101동 202호","lat":37.634,"lng":126.832,"accessNote":null,"workCategory":null}"""
                .formatted(version, phone);
    }

    private ResultActions sendCode(String bearer, String purpose, String phone, String linkToken) throws Exception {
        String json = "{\"purpose\":\"" + purpose + "\",\"phone\":\"" + phone + "\""
                + (linkToken == null ? "" : ",\"linkToken\":\"" + linkToken + "\"") + "}";
        MockHttpServletRequestBuilder request = post(VERIFICATIONS).with(req -> {
            req.setRemoteAddr(ip);
            return req;
        }).contentType(MediaType.APPLICATION_JSON).content(json);
        if (bearer != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        return mockMvc.perform(request);
    }

    private long sendOk(String bearer, String purpose, String phone, String linkToken) throws Exception {
        String body = sendCode(bearer, purpose, phone, linkToken)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.result.verificationId")).longValue();
    }

    private ResultActions confirm(long verificationId, String code) throws Exception {
        return mockMvc.perform(post(VERIFICATIONS + "/" + verificationId + "/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"));
    }

    private ResultActions getAs(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions postAs(String token, String path) throws Exception {
        return mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions putAs(String token, String path, String json) throws Exception {
        return mockMvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
