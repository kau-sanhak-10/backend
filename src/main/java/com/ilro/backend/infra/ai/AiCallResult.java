package com.ilro.backend.infra.ai;

// AI 서버 호출 1회 결과 - 실패도 예외가 아니라 결과로 돌려줌
// retryable: 같은 입력으로 다시 불러 볼 만한 실패 (시간 초과·출력 형식 오류·연결 실패)
//            권한·크레딧·할당량·잘못된 요청·규칙 파일 오류는 다시 불러도 같으므로 false (10/26 결정)
// rawBody: 받은 응답 본문 (JSON이 아니면 저장하지 않음) / errorCode: 실패 종류 (고객 원문·비밀값 없음)
public record AiCallResult(Outcome outcome, boolean retryable, AnalyzeResult result, String rawBody,
                           String errorCode, int latencyMs) {

    public enum Outcome {
        SUCCESS,
        TIMEOUT,
        INVALID_OUTPUT,
        FAILED
    }

    public static AiCallResult success(AnalyzeResult result, String rawBody, int latencyMs) {
        return new AiCallResult(Outcome.SUCCESS, false, result, rawBody, null, latencyMs);
    }

    public static AiCallResult failure(Outcome outcome, boolean retryable, String rawBody, String errorCode,
                                       int latencyMs) {
        return new AiCallResult(outcome, retryable, null, rawBody, errorCode, latencyMs);
    }

    public boolean isSuccess() {
        return outcome == Outcome.SUCCESS;
    }
}
