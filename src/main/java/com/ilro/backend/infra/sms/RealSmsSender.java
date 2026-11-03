package com.ilro.backend.infra.sms;

import com.ilro.backend.global.common.util.PhoneNumbers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

// 솔라피로 실제 문자 발송 (dev·prod). local은 LogSmsSender
// 실패(오류 응답·타임아웃·연결 실패)는 예외 대신 FAILED로 돌려줌 → 인증 로직이 발송 실패로 저장
@Slf4j
@Component
@Profile({"dev", "prod"})
public class RealSmsSender implements SmsSender {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RestClient restClient;
    private final String apiKey;
    private final String apiSecret;
    private final String sender;
    private final Clock clock;

    @Autowired
    public RealSmsSender(@Value("${app.sms.solapi.base-url}") String baseUrl,
                         @Value("${app.sms.solapi.api-key}") String apiKey,
                         @Value("${app.sms.solapi.api-secret}") String apiSecret,
                         @Value("${app.sms.sender}") String sender) {
        this(RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory()).build(),
                apiKey, apiSecret, sender, Clock.systemUTC());
    }

    // 테스트용: 가짜 서버에 연결한 RestClient·고정 시계
    RealSmsSender(RestClient restClient, String apiKey, String apiSecret, String sender, Clock clock) {
        this.restClient = restClient;
        this.apiKey = require(apiKey, "SOLAPI_API_KEY");
        this.apiSecret = require(apiSecret, "SOLAPI_API_SECRET");
        this.sender = require(sender, "SMS_SENDER_NUMBER");
        this.clock = clock;
    }

    @Override
    public SmsSendResult send(String phone, String message) {
        try {
            SendResponse response = restClient.post()
                    .uri("/messages/v4/send-many/fast")
                    .header("Authorization", authorization())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("messages", List.of(Map.of("to", phone, "from", sender, "text", message))))
                    .retrieve()
                    .body(SendResponse.class);
            // 2xx여도 접수 실패한 메시지는 failedMessageList로 옴 (번호 형식·발신번호 미등록 등)
            if (response == null || (response.failedMessageList() != null && !response.failedMessageList().isEmpty())) {
                String reason = response == null ? "empty" : response.failedMessageList().get(0).statusCode();
                log.warn("SMS not accepted: to={}, statusCode={}", PhoneNumbers.mask(phone), reason);
                return SmsSendResult.FAILED;
            }
            return SmsSendResult.SENT;
        } catch (RestClientResponseException e) {
            // 솔라피가 거절 (키·허용 IP·잔액·번호 문제 등)
            log.warn("SMS rejected: to={}, status={}", PhoneNumbers.mask(phone), e.getStatusCode().value());
            return SmsSendResult.FAILED;
        } catch (RuntimeException e) {
            // 타임아웃·연결 실패 등
            log.warn("SMS request failed: to={}, error={}", PhoneNumbers.mask(phone), e.getClass().getSimpleName());
            return SmsSendResult.FAILED;
        }
    }

    // 솔라피 HMAC 인증: signature = HMAC-SHA256(date + salt, apiSecret)
    private String authorization() {
        String date = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString();
        byte[] saltBytes = new byte[16];
        RANDOM.nextBytes(saltBytes);
        String salt = HexFormat.of().formatHex(saltBytes);
        return "HMAC-SHA256 Apikey=" + apiKey + ", Date=" + date + ", salt=" + salt
                + ", signature=" + hmacSha256(apiSecret, date + salt);
    }

    private static String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    // dev·prod에서 값이 비어 있으면 서버가 아예 안 뜨게 → 배포 health 실패 → 자동 롤백
    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " 설정이 필요합니다");
        }
        return value;
    }

    // 솔라피 응답 중 접수 실패 목록만 사용 (나머지 칸은 무시)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SendResponse(List<FailedMessage> failedMessageList) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record FailedMessage(String statusCode, String statusMessage) {
        }
    }
}