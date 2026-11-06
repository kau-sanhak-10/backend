package com.ilro.backend.domain.ai.service;

import com.ilro.backend.domain.ai.service.AiAnalysisStore.Snapshot;
import com.ilro.backend.domain.ai.service.AiAnalysisStore.State;
import com.ilro.backend.infra.ai.AiAnalyzeClient;
import com.ilro.backend.infra.ai.AiCallResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

// 문의 분석 작업 (명세 6장, BE-05·BE-08) - 최신 입력으로 AI 서버를 부르고 결과를 적용
// 같은 입력 버전은 최초 포함 최대 2회, 시도당 30초, 전체 60초 안에서만 다시 부름 (10/26 결정, 재시도는 여기 한 곳에서만)
// 다시 부르기 전에 최신 입력·응대 주체를 확인: 새 입력이 오면 이전 작업은 그만두고 최신 입력으로, 사장님 응대로 바뀌면 멈춤
@Slf4j
@Service
public class AiAnalysisService {

    static final int MAX_ATTEMPTS = 2;
    // 남은 시간이 이보다 짧으면 더 부르지 않음
    private static final Duration MIN_ATTEMPT = Duration.ofSeconds(2);
    // 분석하는 동안 새 입력이 계속 들어올 때 한 작업에서 다시 요청하는 상한 (그 뒤는 다음 요청이 이어 받음)
    private static final int MAX_ROUNDS = 5;

    private final AiAnalysisStore store;
    private final AiAnalyzeClient client;
    private final Clock clock;
    private final Duration attemptTimeout;
    private final Duration totalBudget;

    public AiAnalysisService(AiAnalysisStore store, AiAnalyzeClient client, Clock clock,
                             @Value("${app.ai.attempt-timeout}") Duration attemptTimeout,
                             @Value("${app.ai.total-budget}") Duration totalBudget) {
        this.store = store;
        this.client = client;
        this.clock = clock;
        this.attemptTimeout = attemptTimeout;
        this.totalBudget = totalBudget;
    }

    public void analyze(Long inquiryId) {
        for (int round = 0; round < MAX_ROUNDS; round++) {
            Optional<Snapshot> snapshot = store.prepare(inquiryId);
            if (snapshot.isEmpty() || callUntilSettled(snapshot.get()) != State.NEW_INPUT) {
                return;
            }
        }
        log.warn("AI 분석 중 새 입력이 계속 들어와 다음 요청에 넘김 inquiryId={}", inquiryId);
    }

    private State callUntilSettled(Snapshot snapshot) {
        Instant deadline = clock.instant().plus(totalBudget);
        for (int attempt = snapshot.failedAttempts(); attempt < MAX_ATTEMPTS; attempt++) {
            Duration left = Duration.between(clock.instant(), deadline);
            if (left.compareTo(MIN_ATTEMPT) < 0) {
                break;
            }
            AiCallResult call = checked(snapshot, client.analyze(snapshot.request(),
                    left.compareTo(attemptTimeout) < 0 ? left : attemptTimeout));
            log.info("AI 분석 inquiryId={} inputVersion={} attempt={} outcome={} code={} latencyMs={}",
                    snapshot.inquiryId(), snapshot.inputVersion(), attempt + 1, call.outcome(), call.errorCode(),
                    call.latencyMs());
            State state = call.isSuccess() ? store.apply(snapshot, call) : store.recordFailure(snapshot, call);
            if (state != State.CURRENT) {
                return state;
            }
            if (!call.retryable()) {
                break;
            }
        }
        return store.giveUp(snapshot);
    }

    // 형식은 맞아도 요청과 맞지 않는 결과는 출력 오류로 - 같은 입력으로 한 번 더 (명세 6.4)
    private static AiCallResult checked(Snapshot snapshot, AiCallResult call) {
        if (!call.isSuccess()) {
            return call;
        }
        return AiResultValidator.violation(snapshot.request(), call.result())
                .map(violation -> AiCallResult.failure(AiCallResult.Outcome.INVALID_OUTPUT, true, call.rawBody(),
                        "RULE_VIOLATION " + violation, call.latencyMs()))
                .orElse(call);
    }
}
