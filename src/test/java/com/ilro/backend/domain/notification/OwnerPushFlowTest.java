package com.ilro.backend.domain.notification;

import com.ilro.backend.domain.notification.exception.NotificationErrorCode;
import com.ilro.backend.domain.notification.service.PushSubscriptionService;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.support.TestAccounts;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 사장님 Web Push 구독·알림 수신 설정 (B-27, BE-14)
// 공개키는 테스트 값으로 고정 - 로컬 .env·CI 환경과 상관없이 같은 결과
@SpringBootTest(properties = "app.push.vapid-public-key=test-vapid-public-key")
class OwnerPushFlowTest {

    private static final String SUBSCRIPTIONS = "/api/v1/owner/push-subscriptions";
    private static final String SETTINGS = "/api/v1/owner/notification-settings";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private long ownerId;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        accounts = new TestAccounts(jdbc);
        ownerId = accounts.approvedOwner(accounts.newPhone());
        ownerToken = accounts.ownerToken(ownerId);
    }

    @AfterEach
    void tearDown() {
        accounts.cleanUp();
    }

    // ---------- 공개키 ----------

    @Test
    @DisplayName("공개키 - 설정된 VAPID 공개키를 돌려줌, 사장님 토큰 없으면 401")
    void publicKey() throws Exception {
        getAs(ownerToken, "/api/v1/owner/push/public-key")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.publicKey").value("test-vapid-public-key"));
        mockMvc.perform(get("/api/v1/owner/push/public-key")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("공개키 - 서버에 키가 없으면 PUSH_UNAVAILABLE")
    void publicKeyMissing() {
        PushSubscriptionService service = new PushSubscriptionService(null, " ");

        assertThatThrownBy(service::publicKey)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(NotificationErrorCode.PUSH_UNAVAILABLE);
    }

    // ---------- 구독 ----------

    @Test
    @DisplayName("구독 - 저장, 같은 주소를 다시 올리면 키만 갱신(행 하나), 다른 사장님이 올리면 그 계정으로 옮김")
    void subscribe() throws Exception {
        String endpoint = fcm();

        postAs(ownerToken, SUBSCRIPTIONS, subscription(endpoint, "p1", "a1", "Chrome / Android"))
                .andExpect(status().isOk());
        assertThat(row(endpoint)).containsEntry("owner_id", ownerId).containsEntry("p256dh", "p1")
                .containsEntry("user_agent", "Chrome / Android");

        postAs(ownerToken, SUBSCRIPTIONS, subscription(endpoint, "p2", "a2", null)).andExpect(status().isOk());
        assertThat(count(endpoint)).isEqualTo(1);
        assertThat(row(endpoint)).containsEntry("p256dh", "p2").containsEntry("auth", "a2");

        long otherOwner = accounts.approvedOwner(accounts.newPhone());
        postAs(accounts.ownerToken(otherOwner), SUBSCRIPTIONS, subscription(endpoint, "p3", "a3", null))
                .andExpect(status().isOk());
        assertThat(count(endpoint)).isEqualTo(1);
        assertThat(row(endpoint)).containsEntry("owner_id", otherOwner);
    }

    @Test
    @DisplayName("구독 - 브라우저별 푸시 서버(https) 주소는 받음")
    void knownPushServers() throws Exception {
        for (String host : new String[]{"fcm.googleapis.com", "updates.push.services.mozilla.com",
                "web.push.apple.com", "wns2-par02p.notify.windows.com"}) {
            String endpoint = "https://" + host + "/push/" + UUID.randomUUID();
            postAs(ownerToken, SUBSCRIPTIONS, subscription(endpoint, "p", "a", null)).andExpect(status().isOk());
            assertThat(count(endpoint)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("구독 - 푸시 서버가 아닌 주소는 400, 저장 안 됨 (서버가 엉뚱한 곳으로 요청하지 않게)")
    void rejectsUnknownEndpoint() throws Exception {
        String id = UUID.randomUUID().toString();
        for (String endpoint : new String[]{
                "http://fcm.googleapis.com/send/" + id,                // https 아님
                "https://169.254.169.254/latest/meta-data/" + id,      // 서버 내부 주소
                "https://fcm.googleapis.com@evil.example/" + id,       // 사용자 정보로 속인 주소
                "https://fcm.googleapis.com:8443/send/" + id,          // 다른 포트
                "https://push.apple.com.evil.example/" + id,           // 이름 뒤에 붙인 다른 도메인
                "https://evilpush.apple.com/" + id,                    // 점 없이 붙인 이름
                "not a url " + id}) {
            postAs(ownerToken, SUBSCRIPTIONS, subscription(endpoint, "p", "a", null))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.result[0].field").value("endpoint"));
            assertThat(count(endpoint)).isZero();
        }
    }

    @Test
    @DisplayName("구독 - 키가 없거나 base64url이 아니면 400 (어느 칸인지 알려 줌)")
    void rejectsInvalidKeys() throws Exception {
        postAs(ownerToken, SUBSCRIPTIONS, "{\"endpoint\":\"" + fcm() + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[*].field", contains("keys")));
        postAs(ownerToken, SUBSCRIPTIONS, subscription(fcm(), "not base64!", "a", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[*].field", contains("keys.p256dh")));
    }

    @Test
    @DisplayName("구독 - 긴 브라우저 정보는 255자로 잘라 저장")
    void truncatesUserAgent() throws Exception {
        String endpoint = fcm();
        postAs(ownerToken, SUBSCRIPTIONS, subscription(endpoint, "p", "a", "x".repeat(300)))
                .andExpect(status().isOk());
        assertThat((String) row(endpoint).get("user_agent")).hasSize(255);
    }

    @Test
    @DisplayName("해제 - 내 구독만 지움, 없거나 다른 업체 구독이어도 200(다른 업체 것은 남음)")
    void unsubscribe() throws Exception {
        String mine = fcm();
        String others = fcm();
        postAs(ownerToken, SUBSCRIPTIONS, subscription(mine, "p", "a", null)).andExpect(status().isOk());
        long otherOwner = accounts.approvedOwner(accounts.newPhone());
        postAs(accounts.ownerToken(otherOwner), SUBSCRIPTIONS, subscription(others, "p", "a", null))
                .andExpect(status().isOk());

        deleteAs(ownerToken, SUBSCRIPTIONS, endpointBody(mine)).andExpect(status().isOk());
        assertThat(count(mine)).isZero();
        deleteAs(ownerToken, SUBSCRIPTIONS, endpointBody(mine)).andExpect(status().isOk());

        deleteAs(ownerToken, SUBSCRIPTIONS, endpointBody(others)).andExpect(status().isOk());
        assertThat(count(others)).isEqualTo(1);
    }

    // ---------- 수신 설정 ----------

    @Test
    @DisplayName("설정 - 저장 전이면 모두 켜짐, PUT으로 바꾸면 그대로 읽힘")
    void settings() throws Exception {
        getAs(ownerToken, SETTINGS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.NEW_RESERVATION").value(true))
                .andExpect(jsonPath("$.result.NEEDS_CHECK").value(true))
                .andExpect(jsonPath("$.result.CHANGE").value(true))
                .andExpect(jsonPath("$.result.CANCEL").value(true));

        putAs(ownerToken, SETTINGS,
                "{\"NEW_RESERVATION\":true,\"NEEDS_CHECK\":false,\"CHANGE\":true,\"CANCEL\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.NEEDS_CHECK").value(false));

        getAs(ownerToken, SETTINGS)
                .andExpect(jsonPath("$.result.NEW_RESERVATION").value(true))
                .andExpect(jsonPath("$.result.NEEDS_CHECK").value(false))
                .andExpect(jsonPath("$.result.CHANGE").value(true))
                .andExpect(jsonPath("$.result.CANCEL").value(false));
    }

    @Test
    @DisplayName("설정 - 유형이 빠지거나 null이면 400(빠진 유형 이름), 모르는 키도 400, 저장 안 됨")
    void rejectsIncompleteSettings() throws Exception {
        putAs(ownerToken, SETTINGS, "{\"NEW_RESERVATION\":true,\"NEEDS_CHECK\":null,\"CHANGE\":true}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result[*].field", contains("NEEDS_CHECK", "CANCEL")));
        putAs(ownerToken, SETTINGS,
                "{\"NEW_RESERVATION\":true,\"NEEDS_CHECK\":true,\"CHANGE\":true,\"CANCEL\":true,\"HELLO\":true}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        assertThat(jdbc.queryForObject("SELECT notify_settings FROM owner WHERE id = ?", String.class, ownerId))
                .isNull();
    }

    // ---------- 도우미 ----------

    // 테스트마다 다른 구독 주소 (endpoint는 전체에서 하나만)
    private static String fcm() {
        return "https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID();
    }

    private static String subscription(String endpoint, String p256dh, String auth, String userAgent) {
        return "{\"endpoint\":\"" + endpoint + "\",\"keys\":{\"p256dh\":\"" + p256dh + "\",\"auth\":\"" + auth
                + "\"}" + (userAgent == null ? "" : ",\"userAgent\":\"" + userAgent + "\"") + "}";
    }

    private static String endpointBody(String endpoint) {
        return "{\"endpoint\":\"" + endpoint + "\"}";
    }

    private Map<String, Object> row(String endpoint) {
        return jdbc.queryForMap("SELECT owner_id, p256dh, auth, user_agent FROM push_subscription WHERE endpoint = ?",
                endpoint);
    }

    private int count(String endpoint) {
        return jdbc.queryForObject("SELECT count(*) FROM push_subscription WHERE endpoint = ?", Integer.class,
                endpoint);
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

    private ResultActions deleteAs(String token, String path, String json) throws Exception {
        return mockMvc.perform(delete(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
}