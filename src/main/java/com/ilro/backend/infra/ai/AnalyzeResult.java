package com.ilro.backend.infra.ai;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    // 같은 항목의 서로 다른 값 - 보통 facts엔 그 항목이 없고 여기에만 옴 (AI는 고객에게 다시 묻기만 함)
    // 서버가 그 항목을 미확인(CONFLICT)으로 둠. 후보 전체는 원본(output_raw)에 남음 (명세 6.3)
    public record Conflict(String fieldKey, List<Candidate> candidates) {

        // 서버가 미확인 행에 쓸 후보 - 사장님 값이 아닌 마지막 후보 (가장 최근 고객 설명·사진)
        public Optional<Candidate> latestObserved() {
            if (candidates == null) {
                return Optional.empty();
            }
            for (int i = candidates.size() - 1; i >= 0; i--) {
                Candidate candidate = candidates.get(i);
                if (candidate != null && ("CUSTOMER".equals(candidate.source()) || "PHOTO".equals(candidate.source()))) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        }
    }

    // 충돌 후보 - 기존 값(사장님 값 포함)과 이번에 새로 나온 값. 근거는 있으면 메시지·사진 하나
    public record Candidate(String value, String source, Evidence evidence) {
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
