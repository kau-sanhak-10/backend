package com.ilro.backend.global.common.util;

import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

// 중복 요청 방지 (명세 1.6) - Idempotency-Key 헤더와 요청 지문
// 지문 = SHA-256(요청 경로 + 요청 주체 + 정규화한 본문) - 같은 키로 다른 내용·다른 주체가 오면 409 IDEMPOTENCY_KEY_REUSED
//   본문은 부르는 쪽이 키 순서를 정렬하고 시각을 UTC로 바꾼 JSON으로 넘김
//   주체를 넣어서, 남의 키를 넣어도 다른 사람의 예약 결과를 받아 가지 못함
public final class RequestFingerprint {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final Pattern UUID_FORMAT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private RequestFingerprint() {
    }

    // 요청 키는 UUID - 없거나 형식이 다르면 400, 소문자로 맞춤 (대소문자만 다른 키는 같은 키)
    public static String idempotencyKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalidKey("필수 값입니다");
        }
        String key = raw.trim();
        if (!UUID_FORMAT.matcher(key).matches()) {
            throw invalidKey("UUID 형식이어야 합니다");
        }
        return key.toLowerCase(Locale.ROOT);
    }

    public static String of(String path, String subject, String canonicalBody) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((path + "\n" + subject + "\n" + canonicalBody).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없음", e);
        }
    }

    private static BusinessException invalidKey(String reason) {
        return new BusinessException(CommonErrorCode.INVALID_INPUT,
                List.of(new FieldErrorResponse(IDEMPOTENCY_KEY_HEADER, reason)));
    }
}
