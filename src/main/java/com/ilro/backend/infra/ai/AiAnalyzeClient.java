package com.ilro.backend.infra.ai;

import java.time.Duration;

public interface AiAnalyzeClient {
    // 문의 분석 1회 호출 (POST /internal/v1/inquiries/analyze, 명세 6.2·6.3). 예외를 던지지 않음
    // 다시 부르지 않음 - 같은 입력의 호출 횟수·전체 대기 시간은 AiAnalysisService 한 곳에서 관리 (10/26 결정)
    // app.ai.mode - http: HttpAiAnalyzeClient(실제 AI 서버) / fake: FakeAiAnalyzeClient(서버 없이 가짜 답변)
    AiCallResult analyze(AnalyzeRequest request, Duration timeout);
}
