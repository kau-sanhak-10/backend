package com.ilro.backend.domain.inquiry.entity;

// 현재 응대 주체 - AI와 사장님 동시 답변 방지 (B15)
public enum HandlerMode {
    AI,
    OWNER // OWNER면 AI가 답하지 않음
}
