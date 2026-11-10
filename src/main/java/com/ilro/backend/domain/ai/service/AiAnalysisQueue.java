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
// 문의마다 한 번에 한 작업만. 새 고객 문장이 오면 이전 사진 응답 대기를 끊고 최신 입력을 처리할 수 있다.
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
    private final Map<Long, Work> running = new HashMap<>();

    private static final class Work {
        boolean again;
        volatile long latestTextMessageId;
    }

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
        request(event.inquiryId(), event.customerTextMessageId());
    }

    public void request(Long inquiryId) {
        request(inquiryId, 0);
    }

    private void request(Long inquiryId, long textMessageId) {
        if (!enabled) {
            return;
        }
        Work work;
        synchronized (running) {
            Work current = running.get(inquiryId);
            if (current != null) {
                current.again = true;
                current.latestTextMessageId = Math.max(current.latestTextMessageId, textMessageId);
                return;
            }
            work = new Work();
            work.latestTextMessageId = textMessageId;
            running.put(inquiryId, work);
        }
        try {
            executor.execute(() -> runLoop(inquiryId, work));
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

    private void runLoop(Long inquiryId, Work work) {
        boolean again = true;
        try {
            while (again) {
                try {
                    analysisService.analyze(inquiryId, () -> work.latestTextMessageId);
                } catch (RuntimeException e) {
                    // 답이 없는 메시지는 sweep이 다시 잡음
                    log.error("AI 분석 작업 실패 inquiryId={}", inquiryId, e);
                }
                again = finishRound(inquiryId, work);
            }
        } finally {
            // Error(메모리 부족 등)로 빠져나가도 표시를 지움 - 남으면 이 문의는 재시작 전까지 다시 분석되지 않음
            if (again) {
                synchronized (running) {
                    running.remove(inquiryId);
                }
            }
        }
    }

    // 이번 회차를 끝냄 - 도는 동안 요청이 또 왔으면 한 번 더(true), 아니면 표시를 지움
    private boolean finishRound(Long inquiryId, Work work) {
        synchronized (running) {
            if (work.again) {
                work.again = false;
                return true;
            }
            running.remove(inquiryId);
            return false;
        }
    }
}
