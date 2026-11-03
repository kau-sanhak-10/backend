package com.ilro.backend.infra.sms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RealSmsSenderTest {

    private static final String SEND_URL = "https://api.solapi.com/messages/v4/send-many/fast";
    private static final String API_KEY = "test-key";
    private static final String API_SECRET = "test-secret";
    private static final String SENDER = "01000000000";
    private static final String TO = "01012345678";
    private static final String TEXT = "[일로] 인증번호는 123456입니다.";
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "HMAC-SHA256 Apikey=([^,]+), Date=([^,]+), salt=([0-9a-f]+), signature=([0-9a-f]{64})");

    private MockRestServiceServer server;
    private RealSmsSender sender;

    @BeforeEach
    void setUp() {
        // 진짜 솔라피 대신 가짜 서버에 연결 (bindTo를 build보다 먼저)
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.solapi.com");
        server = MockRestServiceServer.bindTo(builder).build();
        sender = new RealSmsSender(builder.build(), API_KEY, API_SECRET, SENDER, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("솔라피가 2xx로 받으면 SENT - 주소·본문·HMAC 서명 헤더 확인")
    void sendSuccess() {
        server.expect(requestTo(SEND_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.messages[0].to").value(TO))
                .andExpect(jsonPath("$.messages[0].from").value(SENDER))
                .andExpect(jsonPath("$.messages[0].text").value(TEXT))
                .andExpect(request -> assertAuthorization(request.getHeaders().getFirst("Authorization")))
                .andRespond(withSuccess("{\"failedMessageList\":[],\"messageList\":[{\"statusCode\":\"2000\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(sender.send(TO, TEXT)).isEqualTo(SmsSendResult.SENT);
        server.verify();
    }

    @Test
    @DisplayName("200이어도 failedMessageList에 실패가 있으면 FAILED (번호 형식·발신번호 문제 등)")
    void notAccepted() {
        server.expect(requestTo(SEND_URL)).andRespond(withSuccess(
                "{\"failedMessageList\":[{\"to\":\"" + TO + "\",\"statusCode\":\"1062\",\"statusMessage\":\"발신번호 미등록\"}]}",
                MediaType.APPLICATION_JSON));

        assertThat(sender.send(TO, TEXT)).isEqualTo(SmsSendResult.FAILED);
    }

    @Test
    @DisplayName("솔라피가 거절(401)하면 예외 없이 FAILED")
    void rejected() {
        server.expect(requestTo(SEND_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(sender.send(TO, TEXT)).isEqualTo(SmsSendResult.FAILED);
    }

    @Test
    @DisplayName("솔라피 서버 오류(500)도 예외 없이 FAILED")
    void serverError() {
        server.expect(requestTo(SEND_URL)).andRespond(withServerError());

        assertThat(sender.send(TO, TEXT)).isEqualTo(SmsSendResult.FAILED);
    }

    @Test
    @DisplayName("타임아웃·연결 실패도 예외 없이 FAILED")
    void timeout() {
        server.expect(requestTo(SEND_URL)).andRespond(withException(new SocketTimeoutException("timeout")));

        assertThat(sender.send(TO, TEXT)).isEqualTo(SmsSendResult.FAILED);
    }

    @Test
    @DisplayName("키·비밀키·발신번호가 비어 있으면 만들 때 실패 (서버가 안 뜸)")
    void missingConfig() {
        RestClient client = RestClient.create();
        Clock clock = Clock.systemUTC();

        assertThatThrownBy(() -> new RealSmsSender(client, "", API_SECRET, SENDER, clock))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SOLAPI_API_KEY");
        assertThatThrownBy(() -> new RealSmsSender(client, API_KEY, " ", SENDER, clock))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SOLAPI_API_SECRET");
        assertThatThrownBy(() -> new RealSmsSender(client, API_KEY, API_SECRET, null, clock))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SMS_SENDER_NUMBER");
    }

    // 헤더 형식 + 서명이 HMAC-SHA256(date + salt, secret)인지 직접 계산해서 비교
    private static void assertAuthorization(String header) {
        Matcher matcher = AUTHORIZATION.matcher(header);
        assertThat(matcher.matches()).as("Authorization 헤더 형식: %s", header).isTrue();
        assertThat(matcher.group(1)).isEqualTo(API_KEY);
        assertThat(matcher.group(2)).isEqualTo("2026-11-03T12:00:00Z");
        String date = matcher.group(2);
        String salt = matcher.group(3);
        assertThat(matcher.group(4)).isEqualTo(hmacSha256(API_SECRET, date + salt));
    }

    private static String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}