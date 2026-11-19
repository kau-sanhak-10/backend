package com.ilro.backend.infra.push;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

// VAPID 푸시 발송 (BE-14) - 진짜 P-256 키 쌍을 만들어 서명을 공개키로 검증, 푸시 서버는 가짜
class VapidWebPushSenderTest {

    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/test-subscription";
    private static final String SUBJECT = "https://ilro.example";
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private KeyPair keys;
    private String publicKey;
    private String privateKey;
    private MockRestServiceServer server;
    private VapidWebPushSender sender;

    @BeforeEach
    void setUp() throws Exception {
        keys = newKeyPair();
        publicKey = rawPublic(keys);
        privateKey = rawPrivate(keys);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        sender = new VapidWebPushSender(builder.build(), publicKey, privateKey, SUBJECT,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("발송 - 본문 없는 POST, TTL 하루, Authorization의 JWT를 공개키로 검증하면 맞음 (aud·exp·sub)")
    void sendsSignedRequest() {
        server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("TTL", "86400"))
                .andExpect(request -> {
                    String authorization = request.getHeaders().getFirst("Authorization");
                    assertThat(authorization).startsWith("vapid t=").endsWith(", k=" + publicKey);
                    String jwt = authorization.substring("vapid t=".length(), authorization.indexOf(", k="));
                    assertValidJwt(jwt);
                })
                .andRespond(withStatus(HttpStatus.CREATED));

        assertThat(sender.send(ENDPOINT)).isEqualTo(WebPushResult.SENT);
        server.verify();
    }

    @Test
    @DisplayName("발송 결과 - 404·410은 GONE(구독 지움), 그 밖의 실패는 FAILED(다시 보냄)")
    void mapsStatus() {
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.GONE));
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(sender.send(ENDPOINT)).isEqualTo(WebPushResult.GONE);
        assertThat(sender.send(ENDPOINT)).isEqualTo(WebPushResult.GONE);
        assertThat(sender.send(ENDPOINT)).isEqualTo(WebPushResult.FAILED);
        assertThat(sender.send(ENDPOINT)).isEqualTo(WebPushResult.FAILED);
    }

    @Test
    @DisplayName("기동 - 키가 비었거나·형식이 틀리거나·한 쌍이 아니면 서버가 뜨지 않음")
    void rejectsBadKeys() throws Exception {
        String otherPrivate = rawPrivate(newKeyPair());
        RestClient client = RestClient.create();
        Clock clock = Clock.systemUTC();

        assertThatThrownBy(() -> new VapidWebPushSender(client, "", privateKey, SUBJECT, clock))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("VAPID_PUBLIC_KEY");
        assertThatThrownBy(() -> new VapidWebPushSender(client, publicKey, " ", SUBJECT, clock))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("VAPID_PRIVATE_KEY");
        assertThatThrownBy(() -> new VapidWebPushSender(client, publicKey, "c2hvcnQ", SUBJECT, clock))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32바이트");
        assertThatThrownBy(() -> new VapidWebPushSender(client, publicKey, otherPrivate, SUBJECT, clock))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("한 쌍");
    }

    @Test
    @DisplayName("기동 - 연락처(sub)가 https·mailto가 아니면 서버가 뜨지 않음 (애플이 거절)")
    void rejectsBadSubject() {
        RestClient client = RestClient.create();
        Clock clock = Clock.systemUTC();

        for (String subject : new String[]{"http://localhost:3000", "ilro", "https://a\"b"}) {
            assertThatThrownBy(() -> new VapidWebPushSender(client, publicKey, privateKey, subject, clock))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("VAPID_SUBJECT");
        }
        new VapidWebPushSender(client, publicKey, privateKey, "mailto:ops@ilro.example", clock);
    }

    // JWT 세 덩어리 - 헤더 ES256, 내용 aud·exp·sub, 서명은 64바이트(r||s)이고 공개키로 확인됨
    private void assertValidJwt(String jwt) {
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        JsonMapper json = JsonMapper.builder().build();
        assertThat(json.readTree(decode(parts[0])).get("alg").asString()).isEqualTo("ES256");
        JsonNode claims = json.readTree(decode(parts[1]));
        assertThat(claims.get("aud").asString()).isEqualTo("https://fcm.googleapis.com");
        assertThat(claims.get("exp").asLong()).isEqualTo(NOW.plus(VapidWebPushSender.TOKEN_TTL).getEpochSecond());
        assertThat(claims.get("sub").asString()).isEqualTo(SUBJECT);

        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
        assertThat(signature).hasSize(64);
        assertThat(verify(keys.getPublic(), (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8), signature))
                .isTrue();
    }

    private static String decode(String base64url) {
        return new String(Base64.getUrlDecoder().decode(base64url), StandardCharsets.UTF_8);
    }

    private static boolean verify(PublicKey key, byte[] data, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
            verifier.initVerify(key);
            verifier.update(data);
            return verifier.verify(signature);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    // web-push generate-vapid-keys와 같은 모양: 공개키 0x04+x+y 65바이트, 비밀키 d 32바이트 (base64url)
    private static String rawPublic(KeyPair pair) {
        ECPublicKey key = (ECPublicKey) pair.getPublic();
        byte[] point = new byte[65];
        point[0] = 0x04;
        System.arraycopy(fixed(key.getW().getAffineX()), 0, point, 1, 32);
        System.arraycopy(fixed(key.getW().getAffineY()), 0, point, 33, 32);
        return BASE64URL.encodeToString(point);
    }

    private static String rawPrivate(KeyPair pair) {
        return BASE64URL.encodeToString(fixed(((ECPrivateKey) pair.getPrivate()).getS()));
    }

    // BigInteger.toByteArray()는 길이가 31~33바이트로 들쭉날쭉 - 정확히 32바이트로 맞춤
    private static byte[] fixed(BigInteger value) {
        byte[] bytes = value.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(bytes.length, 32);
        System.arraycopy(bytes, bytes.length - copy, out, 32 - copy, copy);
        return out;
    }
}