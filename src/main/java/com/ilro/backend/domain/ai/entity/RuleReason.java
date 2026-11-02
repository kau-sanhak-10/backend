package com.ilro.backend.domain.ai.entity;

// 서버 규칙이 판단한 이유
public enum RuleReason {
    MISSING_FIELD, // 빈칸
    OUT_OF_AREA, // 지역 밖
    SAFETY, // 안전 관련
    LOW_CONFIDENCE, // 확신도 낮음
    DONE // 완료
}
