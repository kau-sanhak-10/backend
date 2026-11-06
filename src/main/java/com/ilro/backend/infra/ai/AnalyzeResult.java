package com.ilro.backend.infra.ai;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

// 문의 분석 결과 (명세 6.3, 공통 응답의 result)
// 값 목록이 있는 칸도 문자열로 받음 - 모르는 값은 읽기 실패가 아니라 AiResultValidator에서 규칙 위반(INVALID_OUTPUT)으로 거름
// 사실(facts·conflicts) / 해석(interpretations) / 다음 행동(nextAction) / 입력 버전을 섞지 않음
public record AnalyzeResult(String requestId, Long inquiryId, Long inputVersion, String ruleVersion,
                            String promptVersion, String decidedBy, String modelName, Usage usage,
                            List<Fact> facts, List<Conflict> conflicts,
                            List<Map<String, Object>> interpretations, WorkCategoryDecision workCategory,
                            Safety safety, String intent, NextAction nextAction, Reply reply,
                            BigDecimal confidence) {

    public record Usage(Integer latencyMs, Integer inputTokens, Integer outputTokens) {
    }

    // source는 AI의 제안 - CUSTOMER(메시지 근거) / PHOTO(사진 근거)만, OWNER는 만들 수 없음
    public record Fact(String fieldKey, String value, String source, String status, String unconfirmedReason,
                       Evidence evidence) {
    }

    public record Evidence(Long messageId, Long photoId) {
    }

    // 같은 항목의 서로 다른 값 - 해당 항목의 미확인(CONFLICT) 제안은 facts로 옴, 후보·근거는 원본(output_raw)에 남김
    public record Conflict(String fieldKey, List<Map<String, Object>> candidates) {
    }

    public record WorkCategoryDecision(String value, String status) {
    }

    public record Safety(Boolean flag, List<String> reasons) {
    }

    public record NextAction(String action, String ruleReason, List<String> targetFieldKeys, String reason) {
    }

    public record Reply(String content) {
    }
}
