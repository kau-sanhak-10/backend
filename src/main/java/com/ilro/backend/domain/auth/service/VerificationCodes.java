package com.ilro.backend.domain.auth.service;

import com.ilro.backend.domain.auth.entity.VerificationPurpose;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

// 6자리 인증번호 생성·해시 (명세 5.1)
// 6자리는 경우의 수가 적어 그냥 해시하면 DB가 새었을 때 바로 되돌릴 수 있음 → 서버 비밀값(pepper)으로 HMAC
// 번호·목적을 같이 넣어 다른 번호·목적의 해시로는 맞지 않게 함
@Component
public class VerificationCodes {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public VerificationCodes(@Value("${app.auth.code-pepper}") String pepper) {
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalStateException("app.auth.code-pepper(AUTH_CODE_PEPPER)가 비어 있습니다");
        }
        this.key = new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String generate() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    public String hash(String phone, VerificationPurpose purpose, String code) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal((phone + ":" + purpose.name() + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // 비교 시간이 일치 여부에 따라 달라지지 않게
    public boolean matches(String phone, VerificationPurpose purpose, String code, String storedHash) {
        byte[] expected = hash(phone, purpose, code).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
