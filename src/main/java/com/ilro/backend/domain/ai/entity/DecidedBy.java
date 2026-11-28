package com.ilro.backend.domain.ai.entity;

// 판단 주체
public enum DecidedBy {
    RULE, // 서버 규칙 (model_name은 null)
    EXTERNAL_API, // 외부 LLM
    SELF_MODEL, // 자체 모델
    JEV // 기존 DB 기록 읽기 호환용. 새 AI 응답에는 허용하지 않는다.
}
