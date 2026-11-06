package com.ilro.backend.domain.ai.service;

import com.ilro.backend.domain.inquiry.repository.InquiryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

// AI 분석 대기열 - 고객 메시지가 커밋되면 따로 스레드에서 분석 (고객 화면은 기다리지 않음, 명세 6.1)
// 문의마다 한 번에 한 작업만: 도는 동안 들어온 요청은 끝난 뒤 한 번 더 돌게 표시 (최신 입력으로)
// 서버 한 대 기준 - 여러 대로 늘리면 문의별 잠금을 DB로 옮겨야 함
@Slf4j
@Component
public class AiAnalysisQueue {

    // 놓친 분석 다시 하기: 이보다 오래 답이 없는 메시지만 (호출 예산 60초 + 여유), 이보다 오래된 메시지는 버림
    private static final Duration SWEEP_AFTER = Duration.ofSeconds(90);
    private static final Duration SWEEP_WITHIN = Duration.ofHours(24);
    private static final int SWEEP_LIMIT = 50;

    private final AiAnalysisService analysisService;
    private final InquiryRepository inquiryRepository;
    private final TaskExecutor executor;
    private final Clock clock;
    private final boolean enabled;
    // 문의 ID → 도는 동안 다시 요청이 왔는지
    private final Map<Long, Boolean> running = new HashMap<>();

    public AiAnalysisQueue(AiAnalysisService analysisService, InquiryRepository inquiryRepository,
                           @Qualifier("aiAnalysisExecutor") TaskExecutor executor, Clock clock,
                           @Value("${app.ai.enabled}") boolean enabled) {
        this.analysisService = analysisService;
        this.inquiryRepository = inquiryRepository;
        this.executor = executor;
        this.clock = clock;
        this.enabled = enabled;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(AiAnalysisRequested event) {
        request(event.inquiryId());
    }

    public void request(Long inquiryId) {
        if (!enabled) {
            return;
        }
        synchronized (running) {
            if (running.containsKey(inquiryId)) {
                running.put(inquiryId, true);
                return;
            }
            running.put(inquiryId, false);
        }
        try {
            executor.execute(() -> runLoop(inquiryId));
        } catch (TaskRejectedException e) {
            synchronized (running) {
                running.remove(inquiryId);
            }
            log.warn("AI 분석 대기열이 가득 차 나중에 다시 확인 inquiryId={}", inquiryId);
        }
    }

    // 서버 재시작·대기열 가득 참으로 놓친 분석 - 답이 없는 고객 메시지가 남은 문의를 다시 요청
    @Scheduled(initialDelayString = "${app.ai.sweep-interval}", fixedDelayString = "${app.ai.sweep-interval}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        Instant now = clock.instant();
        inquiryRepository.findWaitingForAi(now.minus(SWEEP_WITHIN), now.minus(SWEEP_AFTER), SWEEP_LIMIT)
                .forEach(this::request);
    }

    private void runLoop(Long inquiryId) {
        boolean again;
        do {
            try {
                analysisService.analyze(inquiryId);
            } catch (RuntimeException e) {
                // 답이 없는 메시지는 sweep이 다시 잡음
                log.error("AI 분석 작업 실패 inquiryId={}", inquiryId, e);
            }
            synchronized (running) {
                again = Boolean.TRUE.equals(running.get(inquiryId));
                if (again) {
                    running.put(inquiryId, false);
                } else {
                    running.remove(inquiryId);
                }
            }
        } while (again);
    }
}
