package com.ilro.backend.infra.logging;

import com.ilro.backend.global.common.util.AccessLog;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;

// 로컬·테스트용 접근 기록 읽기 - CloudWatch 없이, 이 서버가 켜진 뒤 AccessLog가 남긴 줄을 메모리에 모아 둠
// 서버를 끄면 사라지고 최근 것만 남음 (실제 보관은 서버의 CloudWatch 365일)
@Component
@Profile("!dev & !prod")
@RequiredArgsConstructor
public class InMemoryAccessLogReader implements AccessLogReader {

    static final int CAPACITY = 5_000;

    private final Clock clock;
    private final Deque<AccessLogEntry> entries = new ConcurrentLinkedDeque<>();
    private final Consumer<String> listener = this::remember;

    @PostConstruct
    void attach() {
        AccessLog.addListener(listener);
    }

    @PreDestroy
    void detach() {
        AccessLog.removeListener(listener);
    }

    private void remember(String line) {
        AccessLogEntry.parse(clock.instant(), line).ifPresent(entries::addLast);
        while (entries.size() > CAPACITY) {
            entries.pollFirst();
        }
    }

    @Override
    public Result read(Query query) {
        List<AccessLogEntry> found = entries.stream()
                .filter(entry -> !entry.at().isBefore(query.from()) && entry.at().isBefore(query.to()))
                .filter(entry -> query.targetType() == null || query.targetType().equalsIgnoreCase(entry.targetType()))
                .filter(entry -> query.targetId() == null || query.targetId().equals(entry.targetId()))
                .toList();
        return new Result(found, false);
    }
}
