package com.ilro.backend.global.common.util;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

// 응답 시각은 항상 한국 시간 +09:00 (명세 1.2), 초 단위
public final class KstTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private KstTime() {
    }

    public static OffsetDateTime of(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.SECONDS).atZone(ZONE).toOffsetDateTime();
    }
}
