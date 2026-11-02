package com.ilro.backend.domain.inquiry.entity;

// 사람 응대 요청 상태 - 즉시 응답을 약속하지 않음
public enum HandoffStatus {
    WAITING,
    IN_PROGRESS,
    DONE
}
