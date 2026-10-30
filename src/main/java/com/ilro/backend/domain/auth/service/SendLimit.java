package com.ilro.backend.domain.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

// 누적 발송 제한 하나 (예: 최근 1시간 5회) - 창 안 발송 수가 max 이상이면 줄어들 때까지 기다림
record SendLimit(Duration window, int max) {

    Optional<Duration> waitTime(Collection<Instant> sendTimes, Instant now) {
        Instant windowStart = now.minus(window);
        List<Instant> inWindow = sendTimes.stream().filter(time -> time.isAfter(windowStart)).sorted().toList();
        if (inWindow.size() < max) {
            return Optional.empty();
        }
        // 창 안 발송이 max - 1개로 줄어드는 시각 = 앞에서 (size - max)번째 발송이 창을 벗어나는 때
        Instant freedAt = inWindow.get(inWindow.size() - max).plus(window);
        return Optional.of(Duration.between(now, freedAt));
    }
}
