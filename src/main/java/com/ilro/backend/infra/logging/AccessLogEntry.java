package com.ilro.backend.infra.logging;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 접근 기록 한 줄 (명세 1.8·5.9) - AccessLog가 남긴 줄을 다시 읽은 것
// targetId는 대상이 하나로 정해지지 않는 기록(예: 삭제 요청 전체 결과)이면 null
public record AccessLogEntry(Instant at, String actorType, Long actorId, String action, String targetType,
                             Long targetId, String result) {

    // AccessLog의 줄 형식 - 바꾸면 두 곳을 같이 고칠 것
    private static final Pattern LINE = Pattern.compile(
            "actor=(\\w+):(-?\\d+|null) action=(\\w+) target=([\\w.]+):(-?\\d+|null) result=(\\w+)");

    // 형식이 다른 줄(접근 기록이 아닌 로그)은 empty
    public static Optional<AccessLogEntry> parse(Instant at, String message) {
        if (message == null) {
            return Optional.empty();
        }
        Matcher matcher = LINE.matcher(message);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(new AccessLogEntry(at, matcher.group(1), id(matcher.group(2)), matcher.group(3),
                matcher.group(4), id(matcher.group(5)), matcher.group(6)));
    }

    private static Long id(String value) {
        return "null".equals(value) ? null : Long.valueOf(value);
    }
}
