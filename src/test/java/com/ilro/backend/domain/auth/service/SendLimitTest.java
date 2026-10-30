package com.ilro.backend.domain.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SendLimitTest {

    private static final Instant NOW = Instant.parse("2026-10-30T03:00:00Z");
    private final SendLimit hourlyFive = new SendLimit(Duration.ofHours(1), 5);

    @Test
    @DisplayName("창 안 발송이 한도보다 적으면 기다리지 않는다")
    void underLimit() {
        assertThat(hourlyFive.waitTime(minutesAgo(50, 40, 30, 20), NOW)).isEmpty();
    }

    @Test
    @DisplayName("창 밖 발송은 세지 않는다")
    void oldSendsAreIgnored() {
        assertThat(hourlyFive.waitTime(minutesAgo(90, 61, 30, 20, 10), NOW)).isEmpty();
    }

    @Test
    @DisplayName("한도에 닿으면 가장 오래된 발송이 창을 벗어날 때까지 기다린다")
    void waitsUntilOldestLeavesWindow() {
        assertThat(hourlyFive.waitTime(minutesAgo(50, 40, 30, 20, 10), NOW)).contains(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("한도를 넘게 쌓였으면 한도 아래로 줄어들 때까지 기다린다")
    void waitsUntilBelowLimitWhenOver() {
        // 6건 → 4건이 되려면 앞의 2건이 빠져야 함 → 두 번째로 오래된 50분 전 발송 기준 10분
        assertThat(hourlyFive.waitTime(minutesAgo(55, 50, 40, 30, 20, 10), NOW)).contains(Duration.ofMinutes(10));
    }

    private static List<Instant> minutesAgo(long... minutes) {
        return java.util.Arrays.stream(minutes).mapToObj(m -> NOW.minus(Duration.ofMinutes(m))).toList();
    }
}
