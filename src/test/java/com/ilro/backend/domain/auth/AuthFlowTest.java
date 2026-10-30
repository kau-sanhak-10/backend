package com.ilro.backend.domain.auth;

import com.ilro.backend.domain.auth.service.AuthTokenService;
import com.ilro.backend.global.common.response.ApiResponse;
import com.ilro.backend.support.CapturingSmsSender;
import com.ilro.backend.support.TestAccounts;
import com.ilro.backend.support.TestSmsConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 문자 인증 → 토큰 발급 → 경로별 토큰 확인 (BE-02, 명세 4.1·5.1·7.2)
// 틀린 입력·발송 실패 기록이 오류 응답 뒤에도 남는지 봐야 해서 테스트 트랜잭션 없이 실제로 저장하고 지움
@SpringBootTest
@Import({TestSmsConfig.class, AuthFlowTest.AdminPingController.class})
class AuthFlowTest {

    private static final String VERIFICATIONS = "/api/v1/auth/verifications";
    private static final String OWNER_ME = "/api/v1/owner/me";
    private static final String ADMIN_PING = "/api/v1/admin/test-ping";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CapturingSmsSender smsSender;
    @Autowired
    private AuthTokenService authTokenService;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private String ip;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        accounts = new TestAccounts(jdbc);
        ip = TestAccounts.newIp();
    }

    @AfterEach
    void tearDown() {
        accounts.cleanUp();
    }

    @Test
    @DisplayName("사장님 로그인 - 하이픈 번호로 받아 6자리 확인 → 7일 토큰, 내 정보 조회, 로그아웃하면 401")
    void ownerLoginFlow() throws Exception {
        String phone = accounts.newPhone();
        long ownerId = accounts.approvedOwner(phone);
        String hyphenated = phone.substring(0, 3) + "-" + phone.substring(3, 7) + "-" + phone.substring(7);

        String sendBody = sendCode("OWNER_LOGIN", hyphenated)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.expiresAt").value(endsWith("+09:00")))
                .andExpect(jsonPath("$.result.resendAvailableAt").value(endsWith("+09:00")))
                .andReturn().getResponse().getContentAsString();
        long verificationId = ((Number) JsonPath.read(sendBody, "$.result.verificationId")).longValue();
        String code = smsSender.lastCode(phone);
        assertThat(code).matches("\\d{6}");

        // 숫자만 저장, 인증번호 원문은 저장하지 않음, 대상 업체·요청 IP 기록
        var row = jdbc.queryForMap("SELECT phone, code_hash, target_owner_id, request_ip, send_status "
                + "FROM phone_verification WHERE id = ?", verificationId);
        assertThat(row.get("phone")).isEqualTo(phone);
        assertThat((String) row.get("code_hash")).isNotEqualTo(code).doesNotContain(code);
        assertThat(row.get("target_owner_id")).isEqualTo(ownerId);
        assertThat(row.get("request_ip")).isEqualTo(ip);
        assertThat(row.get("send_status")).isEqualTo("SENT");

        String confirmBody = confirm(verificationId, code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.purpose").value("OWNER_LOGIN"))
                .andExpect(jsonPath("$.result.accessToken").value(startsWith("own_")))
                .andExpect(jsonPath("$.result.owner.ownerId").value(ownerId))
                .andExpect(jsonPath("$.result.owner.approvalStatus").value("APPROVED"))
                .andExpect(jsonPath("$.result.admin").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(confirmBody, "$.result.accessToken");

        // 토큰도 해시만 저장, 만료는 7일 뒤
        Timestamp expiresAt = jdbc.queryForObject(
                "SELECT expires_at FROM auth_token WHERE token_hash = ? AND owner_id = ?",
                Timestamp.class, TestAccounts.sha256(token), ownerId);
        assertThat(Duration.between(Instant.now(), expiresAt.toInstant()))
                .isBetween(Duration.ofDays(7).minusMinutes(1), Duration.ofDays(7));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_token WHERE token_hash = ?", Integer.class, token))
                .isZero();

        mockMvc.perform(get(OWNER_ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.ownerId").value(ownerId))
                .andExpect(jsonPath("$.result.phone").value(phone));

        mockMvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get(OWNER_ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("승인됐지만 아직 준비 중(READY)인 업체도 로그인할 수 있다")
    void readyOwnerCanLogIn() throws Exception {
        String phone = accounts.newPhone();
        accounts.owner(phone, "APPROVED", "READY", null);

        long verificationId = sendOk("OWNER_LOGIN", phone);
        confirm(verificationId, smsSender.lastCode(phone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.owner.status").value("READY"));
    }

    @Test
    @DisplayName("휴대전화 형식이 아니면 400 INVALID_INPUT, 칸 이름 phone")
    void invalidPhone() throws Exception {
        sendCode("OWNER_LOGIN", "02-123-4567")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result[0].field").value("phone"));
    }

    @Test
    @DisplayName("등록되지 않은 번호는 문자 없이 404 OWNER_NOT_REGISTERED")
    void unregisteredOwner() throws Exception {
        String phone = accounts.newPhone();

        sendCode("OWNER_LOGIN", phone)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OWNER_NOT_REGISTERED"));
        assertThat(smsSender.hasSentTo(phone)).isFalse();
        assertThat(verificationCount(phone)).isZero();
    }

    @ParameterizedTest(name = "{0}/{1} → {3}")
    @CsvSource({
            "PENDING, READY, , OWNER_APPROVAL_PENDING",
            "REJECTED, READY, 사업자 정보 불일치, OWNER_REJECTED",
            "APPROVED, RESTRICTED, , OWNER_RESTRICTED",
            "APPROVED, WITHDRAWN, , OWNER_WITHDRAWN",
    })
    @DisplayName("승인 전·거절·이용 제한·탈퇴 업체는 문자 없이 403")
    void blockedOwnerGetsNoCode(String approvalStatus, String status, String rejectionReason, String code)
            throws Exception {
        String phone = accounts.newPhone();
        accounts.owner(phone, approvalStatus, status, rejectionReason);

        ResultActions result = sendCode("OWNER_LOGIN", phone)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(code));
        if (rejectionReason != null) {
            result.andExpect(jsonPath("$.result.rejectionReason").value(rejectionReason));
        }
        assertThat(smsSender.hasSentTo(phone)).isFalse();
        assertThat(verificationCount(phone)).isZero();
    }

    @Test
    @DisplayName("틀리면 남은 횟수를 알려 주고, 5번째에 429 - 그 뒤엔 맞는 번호도 거절")
    void wrongCodeAttemptsAreCounted() throws Exception {
        String phone = accounts.newPhone();
        accounts.approvedOwner(phone);
        long verificationId = sendOk("OWNER_LOGIN", phone);
        String code = smsSender.lastCode(phone);
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int attemptsLeft = 4; attemptsLeft >= 1; attemptsLeft--) {
            confirm(verificationId, wrong)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VERIFICATION_CODE_MISMATCH"))
                    .andExpect(jsonPath("$.result.attemptsLeft").value(attemptsLeft));
        }
        confirm(verificationId, wrong)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("VERIFICATION_ATTEMPTS_EXCEEDED"));
        // 오류 응답이어도 시도 횟수는 저장됨
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM phone_verification WHERE id = ?",
                Integer.class, verificationId)).isEqualTo(5);

        confirm(verificationId, code)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("VERIFICATION_ATTEMPTS_EXCEEDED"));
    }

    @Test
    @DisplayName("이미 쓴 인증번호를 다시 확인하면 409")
    void codeIsSingleUse() throws Exception {
        String phone = accounts.newPhone();
        accounts.approvedOwner(phone);
        long verificationId = sendOk("OWNER_LOGIN", phone);
        String code = smsSender.lastCode(phone);

        confirm(verificationId, code).andExpect(status().isOk());
        confirm(verificationId, code)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERIFICATION_ALREADY_USED"));
    }

    @Test
    @DisplayName("5분이 지나면 400 VERIFICATION_EXPIRED")
    void codeExpires() throws Exception {
        String phone = accounts.newPhone();
        accounts.approvedOwner(phone);
        long verificationId = sendOk("OWNER_LOGIN", phone);
        shiftSendTimes(phone, Duration.ofMinutes(6));

        confirm(verificationId, smsSender.lastCode(phone))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFICATION_EXPIRED"));
    }

    @Test
    @DisplayName("60초 안 재발송은 429 + Retry-After, 지나서 다시 받으면 이전 번호는 무효")
    void resendIntervalAndReplacement() throws Exception {
        String phone = accounts.newPhone();
        accounts.approvedOwner(phone);
        long firstId = sendOk("OWNER_LOGIN", phone);
        String firstCode = smsSender.lastCode(phone);

        sendCode("OWNER_LOGIN", phone)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("VERIFICATION_RESEND_TOO_SOON"))
                .andExpect(jsonPath("$.result.retryAfterSeconds").value(allOf(greaterThanOrEqualTo(50), lessThanOrEqualTo(60))))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));

        shiftSendTimes(phone, Duration.ofSeconds(61));
        long secondId = sendOk("OWNER_LOGIN", phone);
        String secondCode = smsSender.lastCode(phone);

        confirm(firstId, firstCode)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFICATION_EXPIRED"));
        confirm(secondId, secondCode).andExpect(status().isOk());
    }

    @Test
    @DisplayName("번호별 최근 1시간 5회 - 인증에 성공한 발송도 센다")
    void phoneHourlyLimit() throws Exception {
        String phone = accounts.newPhone();
        accounts.approvedOwner(phone);
        for (int minutesAgo : new int[]{50, 40, 30, 20, 10}) {
            seedSend(phone, TestAccounts.newIp(), Duration.ofMinutes(minutesAgo), true);
        }

        // 가장 오래된 50분 전 발송이 1시간 창을 벗어나는 10분 뒤
        sendCode("OWNER_LOGIN", phone)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("VERIFICATION_SEND_LIMITED"))
                .andExpect(jsonPath("$.result.retryAfterSeconds").value(allOf(greaterThanOrEqualTo(590), lessThanOrEqualTo(600))))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        assertThat(smsSender.hasSentTo(phone)).isFalse();
    }

    @Test
    @DisplayName("번호별 최근 24시간 10회")
    void phoneDailyLimit() throws Exception {
        String phone = accounts.newPhone();
        accounts.approvedOwner(phone);
        for (int hoursAgo = 23; hoursAgo >= 14; hoursAgo--) {
            seedSend(phone, TestAccounts.newIp(), Duration.ofHours(hoursAgo), false);
        }

        sendCode("OWNER_LOGIN", phone)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("VERIFICATION_SEND_LIMITED"))
                .andExpect(jsonPath("$.result.retryAfterSeconds").value(allOf(greaterThanOrEqualTo(3590), lessThanOrEqualTo(3600))));
    }

    @Test
    @DisplayName("IP별 최근 1시간 30회 - 번호가 달라도 같은 IP면 막는다")
    void ipHourlyLimit() throws Exception {
        for (int i = 0; i < 30; i++) {
            seedSend(accounts.newPhone(), ip, Duration.ofMinutes(55 - i), false);
        }
        String phone = accounts.newPhone();
        accounts.approvedOwner(phone);

        sendCode("OWNER_LOGIN", phone)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("VERIFICATION_SEND_LIMITED"));
        assertThat(smsSender.hasSentTo(phone)).isFalse();
    }

    @Test
    @DisplayName("문자 발송 실패는 502, 실패 기록은 남고 그 인증으로는 확인할 수 없다")
    void sendFailure() throws Exception {
        String phone = accounts.newPhone();
        accounts.approvedOwner(phone);
        smsSender.failFor(phone);

        sendCode("OWNER_LOGIN", phone)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("VERIFICATION_SEND_FAILED"));

        var row = jdbc.queryForMap("SELECT id, send_status FROM phone_verification WHERE phone = ?", phone);
        assertThat(row.get("send_status")).isEqualTo("FAILED");
        confirm(((Number) row.get("id")).longValue(), "123456")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VERIFICATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("운영자 로그인 → 12시간 토큰, 운영자 경로만 통과")
    void adminLoginFlow() throws Exception {
        String phone = accounts.newPhone();
        long adminId = accounts.admin(phone);

        long verificationId = sendOk("ADMIN_LOGIN", phone);
        String body = confirm(verificationId, smsSender.lastCode(phone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.purpose").value("ADMIN_LOGIN"))
                .andExpect(jsonPath("$.result.accessToken").value(startsWith("adm_")))
                .andExpect(jsonPath("$.result.admin.adminId").value(adminId))
                .andExpect(jsonPath("$.result.owner").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.result.accessToken");

        Timestamp expiresAt = jdbc.queryForObject("SELECT expires_at FROM auth_token WHERE token_hash = ?",
                Timestamp.class, TestAccounts.sha256(token));
        assertThat(Duration.between(Instant.now(), expiresAt.toInstant()))
                .isBetween(Duration.ofHours(12).minusMinutes(1), Duration.ofHours(12));

        mockMvc.perform(get(ADMIN_PING).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get(OWNER_ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("운영자로 등록되지 않은 번호는 문자 없이 403")
    void unknownAdmin() throws Exception {
        String phone = accounts.newPhone();

        sendCode("ADMIN_LOGIN", phone)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(smsSender.hasSentTo(phone)).isFalse();
    }

    @Test
    @DisplayName("아직 없는 인증 목적·모르는 목적 값·6자리가 아닌 번호는 400")
    void unsupportedInputs() throws Exception {
        sendCode("CUSTOMER_VERIFY", accounts.newPhone())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result[0].field").value("purpose"));
        sendCode("SUPER_LOGIN", accounts.newPhone())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        confirm(1L, "12ab56")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("사장님 토큰은 운영자 경로에서, 고객 토큰은 사장님 경로에서 403")
    void tokenTypeMustMatchPath() throws Exception {
        String phone = accounts.newPhone();
        long ownerId = accounts.approvedOwner(phone);
        String ownerToken = accounts.ownerToken(ownerId);
        String customerToken = accounts.token("CUSTOMER", ownerId, null, accounts.newPhone(), "RESERVATION_LOOKUP",
                "CURRENT_TIMESTAMP + interval '1 day'");

        mockMvc.perform(get(ADMIN_PING).header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get(OWNER_ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("토큰 없음·형식 틀림·모르는 토큰은 401 UNAUTHORIZED, 만료는 401 TOKEN_EXPIRED")
    void tokenErrors() throws Exception {
        long ownerId = accounts.approvedOwner(accounts.newPhone());
        String expired = accounts.token("OWNER", ownerId, null, null, null, "CURRENT_TIMESTAMP - interval '1 minute'");

        mockMvc.perform(get(OWNER_ME))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get(OWNER_ME).header(HttpHeaders.AUTHORIZATION, "Basic abc"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get(OWNER_ME).header(HttpHeaders.AUTHORIZATION, "Bearer own_unknown"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get(OWNER_ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Bearer는 대소문자를 가리지 않는다")
    void bearerIsCaseInsensitive() throws Exception {
        long ownerId = accounts.approvedOwner(accounts.newPhone());

        mockMvc.perform(get(OWNER_ME).header(HttpHeaders.AUTHORIZATION, "bearer " + accounts.ownerToken(ownerId)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("로그인 뒤 이용 제한되면 다음 요청부터 403 OWNER_RESTRICTED")
    void restrictedAfterLogin() throws Exception {
        long ownerId = accounts.approvedOwner(accounts.newPhone());
        String token = accounts.ownerToken(ownerId);
        jdbc.update("UPDATE owner SET status = 'RESTRICTED' WHERE id = ?", ownerId);

        mockMvc.perform(get(OWNER_ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWNER_RESTRICTED"));
    }

    @Test
    @DisplayName("업체 토큰 일괄 철회 - 그 업체의 모든 토큰이 401, 다른 업체 토큰은 그대로")
    void revokeOwnerTokens() throws Exception {
        long ownerId = accounts.approvedOwner(accounts.newPhone());
        long otherOwnerId = accounts.approvedOwner(accounts.newPhone());
        String first = accounts.ownerToken(ownerId);
        String second = accounts.ownerToken(ownerId);
        String other = accounts.ownerToken(otherOwnerId);

        authTokenService.revokeOwnerTokens(ownerId);

        for (String token : new String[]{first, second}) {
            mockMvc.perform(get(OWNER_ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(get(OWNER_ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.ownerId").value(otherOwnerId));
    }

    private ResultActions sendCode(String purpose, String phone) throws Exception {
        return mockMvc.perform(withIp(post(VERIFICATIONS))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"" + purpose + "\",\"phone\":\"" + phone + "\"}"));
    }

    private long sendOk(String purpose, String phone) throws Exception {
        String body = sendCode(purpose, phone)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.result.verificationId")).longValue();
    }

    private ResultActions confirm(long verificationId, String code) throws Exception {
        return mockMvc.perform(post(VERIFICATIONS + "/" + verificationId + "/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"));
    }

    private MockHttpServletRequestBuilder withIp(MockHttpServletRequestBuilder request) {
        return request.with(req -> {
            req.setRemoteAddr(ip);
            return req;
        });
    }

    // 이미 지나간 발송 기록 (누적 제한 확인용)
    private void seedSend(String phone, String requestIp, Duration ago, boolean verified) {
        jdbc.update("INSERT INTO phone_verification (phone, purpose, code_hash, send_status, expires_at, created_at, "
                        + "verified_at, request_ip) VALUES (?, 'OWNER_LOGIN', 'seeded', 'SENT', "
                        + "CURRENT_TIMESTAMP - make_interval(secs => ?) + interval '5 minutes', "
                        + "CURRENT_TIMESTAMP - make_interval(secs => ?), "
                        + "CASE WHEN ? THEN CURRENT_TIMESTAMP - make_interval(secs => ?) + interval '1 minute' END, ?)",
                phone, ago.toSeconds(), ago.toSeconds(), verified, ago.toSeconds(), requestIp);
    }

    // 그 번호의 발송을 과거로 옮김 (시간이 지난 것처럼)
    private void shiftSendTimes(String phone, Duration back) {
        jdbc.update("UPDATE phone_verification SET created_at = created_at - make_interval(secs => ?), "
                + "expires_at = expires_at - make_interval(secs => ?) WHERE phone = ?", back.toSeconds(), back.toSeconds(), phone);
    }

    private int verificationCount(String phone) {
        return jdbc.queryForObject("SELECT count(*) FROM phone_verification WHERE phone = ?", Integer.class, phone);
    }

    // 운영자 토큰 경로 확인용 (운영자 API는 BE-08 이후)
    @RestController
    public static class AdminPingController {

        @GetMapping(ADMIN_PING)
        public ApiResponse<Void> ping() {
            return ApiResponse.onSuccess();
        }
    }
}
