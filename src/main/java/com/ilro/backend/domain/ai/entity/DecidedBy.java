package com.ilro.backend.domain.ai.entity;

// 판단 주체
public enum DecidedBy {
    RULE, // 서버 규칙 (model_name은 null)
    EXTERNAL_API, // 외부 LLM
    SELF_MODEL, // 자체 모델
    JEV // 비교 실험
}
