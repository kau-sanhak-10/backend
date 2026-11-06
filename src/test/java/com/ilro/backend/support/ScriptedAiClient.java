package com.ilro.backend.support;

import com.ilro.backend.infra.ai.AiAnalyzeClient;
import com.ilro.backend.infra.ai.AiCallResult;
import com.ilro.backend.infra.ai.AnalyzeRequest;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

// 테스트용 AI 서버 - 미리 정한 답을 차례로 돌려주고 받은 요청·대기 시간을 남김
// 정한 답보다 많이 부르면 실패 (부르지 말아야 할 때 부르는지 확인)
public class ScriptedAiClient implements AiAnalyzeClient {

    private final Deque<Function<AnalyzeRequest, AiCallResult>> script = new ArrayDeque<>();
    private final List<AnalyzeRequest> requests = new ArrayList<>();
    private final List<Duration> timeouts = new ArrayList<>();

    public synchronized void reset() {
        script.clear();
        requests.clear();
        timeouts.clear();
    }

    public synchronized ScriptedAiClient then(Function<AnalyzeRequest, AiCallResult> answer) {
        script.add(answer);
        return this;
    }

    public synchronized List<AnalyzeRequest> requests() {
        return List.copyOf(requests);
    }

    public synchronized List<Duration> timeouts() {
        return List.copyOf(timeouts);
    }

    @Override
    public AiCallResult analyze(AnalyzeRequest request, Duration timeout) {
        Function<AnalyzeRequest, AiCallResult> answer;
        synchronized (this) {
            requests.add(request);
            timeouts.add(timeout);
            answer = script.poll();
        }
        if (answer == null) {
            throw new AssertionError("정하지 않은 AI 호출 inputVersion=" + request.inputVersion());
        }
        return answer.apply(request);
    }
}
