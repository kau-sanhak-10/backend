package com.ilro.backend.infra.push;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;

// 실제 브라우저 푸시 발송 (Web Push + VAPID, RFC 8030·8292) - 내용 없는 푸시라 암호화(RFC 8291)는 하지 않음
// 요청: POST {endpoint}, 본문 없음, Authorization: vapid t={JWT}, k={공개키}, TTL: 하루
//   JWT = ES256 서명 {aud: 푸시 서버 주소, exp: 12시간 뒤, sub: 연락처} - "일로 서버가 보낸 푸시"임을 증명
// dev·prod에서 키가 비었거나 짝이 안 맞으면 서버가 아예 안 뜨게 → 배포 health 실패 → 자동 롤백
@Slf4j
@Component
@Profile({"dev", "prod"})
public class VapidWebPushSender implements WebPushSender {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    // JWT 유효시간 - 푸시 서버는 24시간 넘는 토큰을 거절함
    static final Duration TOKEN_TTL = Duration.ofHours(12);
    // 폰이 꺼져 있으면 푸시 서버가 이 시간(초) 동안 보관했다가 켜질 때 전달
    static final String MESSAGE_TTL_SECONDS = "86400";

    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
    private static final String JWT_HEADER = BASE64URL.encodeToString(
            "{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));

    private final RestClient restClient;
    private final String publicKey;
    private final PrivateKey privateKey;
    private final String subject;
    private final Clock clock;

    @Autowired
    public VapidWebPushSender(@Value("${app.push.vapid-public-key}") String publicKey,
                              @Value("${app.push.vapid-private-key}") String privateKey,
                              @Value("${app.push.vapid-subject}") String subject) {
        this(RestClient.builder().requestFactory(requestFactory()).build(), publicKey, privateKey, subject,
                Clock.systemUTC());
    }

    // 실제 초기화(키 검사 포함) - 위 생성자가 이걸 부르고, 테스트는 가짜 서버 RestClient·고정 시계로 직접 부름
    VapidWebPushSender(RestClient restClient, String publicKey, String privateKey, String subject, Clock clock) {
        this.restClient = restClient;
        this.publicKey = require(publicKey, "VAPID_PUBLIC_KEY");
        this.privateKey = toPrivateKey(require(privateKey, "VAPID_PRIVATE_KEY"));
        this.subject = requireSubject(subject);
        this.clock = clock;
        requirePair(toPublicKey(this.publicKey), this.privateKey);
    }

    @Override
    public WebPushResult send(String endpoint) {
        URI uri = URI.create(endpoint);
        try {
            HttpStatusCode status = restClient.post()
                    .uri(uri)
                    .header("Authorization", "vapid t=" + token(audienceOf(uri)) + ", k=" + publicKey)
                    .header("TTL", MESSAGE_TTL_SECONDS)
                    .retrieve()
                    .toBodilessEntity()
                    .getStatusCode();
            // 2xx만 보냄 - 3xx는 오류로 던지지 않고 여기로 옴. 리다이렉트는 따라가지 않음
            // (구독 주소는 허용 목록으로 검사한 곳으로만 보내야 해서) → 실패로 두고 다시 보냄
            if (!status.is2xxSuccessful()) {
                log.warn("Push not accepted: host={}, status={}", uri.getHost(), status.value());
                return WebPushResult.FAILED;
            }
            return WebPushResult.SENT;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            // 구독 주소는 그 자체가 비밀이라 로그에는 푸시 서버 이름만
            if (status == 404 || status == 410) {
                log.info("Push subscription gone: host={}, status={}", uri.getHost(), status);
                return WebPushResult.GONE;
            }
            log.warn("Push rejected: host={}, status={}", uri.getHost(), status);
            return WebPushResult.FAILED;
        } catch (RuntimeException e) {
            // 시간 초과·연결 실패 등
            log.warn("Push request failed: host={}, error={}", uri.getHost(), e.getClass().getSimpleName());
            return WebPushResult.FAILED;
        }
    }

    // VAPID JWT (ES256) - header.claims.signature
    String token(String audience) {
        long exp = clock.instant().plus(TOKEN_TTL).getEpochSecond();
        String claims = BASE64URL.encodeToString(("{\"aud\":\"" + audience + "\",\"exp\":" + exp + ",\"sub\":\""
                + subject + "\"}").getBytes(StandardCharsets.UTF_8));
        String unsigned = JWT_HEADER + "." + claims;
        return unsigned + "." + BASE64URL.encodeToString(sign(privateKey, unsigned.getBytes(StandardCharsets.UTF_8)));
    }

    // 푸시 서버 주소(origin) - 예: https://fcm.googleapis.com
    static String audienceOf(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }

    // ES256 서명 - JWT는 DER이 아니라 r||s 64바이트 형식 (P1363)
    private static byte[] sign(PrivateKey key, byte[] data) {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(key);
            signature.update(data);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("VAPID 서명에 실패했습니다", e);
        }
    }

    // 비밀키 = P-256 개인 값 d 32바이트 (base64url, web-push generate-vapid-keys 형식)
    private static PrivateKey toPrivateKey(String base64url) {
        byte[] d = decode(base64url, "VAPID_PRIVATE_KEY");
        if (d.length != 32) {
            throw new IllegalStateException("VAPID_PRIVATE_KEY는 32바이트여야 합니다");
        }
        try {
            return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d), p256()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("VAPID_PRIVATE_KEY를 읽을 수 없습니다", e);
        }
    }

    // 공개키 = 압축하지 않은 점 65바이트 (0x04 + x 32바이트 + y 32바이트)
    private static PublicKey toPublicKey(String base64url) {
        byte[] point = decode(base64url, "VAPID_PUBLIC_KEY");
        if (point.length != 65 || point[0] != 0x04) {
            throw new IllegalStateException("VAPID_PUBLIC_KEY는 65바이트 압축하지 않은 P-256 공개키여야 합니다");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(point, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(point, 33, 65));
        try {
            return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), p256()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("VAPID_PUBLIC_KEY를 읽을 수 없습니다", e);
        }
    }

    // 두 키가 한 쌍인지 - 비밀키로 서명한 것을 공개키로 확인 (Parameter Store에 다른 쌍을 섞어 넣은 경우를 기동 때 잡음)
    private static void requirePair(PublicKey publicKey, PrivateKey privateKey) {
        byte[] sample = "ilro-vapid-check".getBytes(StandardCharsets.UTF_8);
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
            verifier.initVerify(publicKey);
            verifier.update(sample);
            if (!verifier.verify(sign(privateKey, sample))) {
                throw new IllegalStateException("VAPID 공개키와 비밀키가 한 쌍이 아닙니다");
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("VAPID 키를 확인할 수 없습니다", e);
        }
    }

    private static ECParameterSpec p256() throws GeneralSecurityException {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        return parameters.getParameterSpec(ECParameterSpec.class);
    }

    private static byte[] decode(String base64url, String name) {
        try {
            return Base64.getUrlDecoder().decode(base64url.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(name + "가 base64url 형식이 아닙니다");
        }
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " 설정이 필요합니다");
        }
        return value.strip();
    }

    // 애플은 sub가 https 주소나 mailto가 아니면 거절 - JWT에 그대로 넣으므로 따옴표·역슬래시도 막음
    private static String requireSubject(String subject) {
        String value = require(subject, "VAPID_SUBJECT");
        if (!(value.startsWith("https://") || value.startsWith("mailto:"))
                || value.contains("\"") || value.contains("\\")) {
            throw new IllegalStateException("VAPID_SUBJECT는 https 주소나 mailto여야 합니다");
        }
        return value;
    }

    // 리다이렉트는 따라가지 않음 (JDK 기본) - 푸시 서버가 다른 주소로 보내도 그쪽으로 요청하지 않음
    private static JdkClientHttpRequestFactory requestFactory() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}