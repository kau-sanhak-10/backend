package com.ilro.backend.domain.ai.service;

// 고객 메시지가 저장됨 - 커밋된 뒤 AI 분석을 시작 (AiAnalysisQueue)
public record AiAnalysisRequested(Long inquiryId) {
}
