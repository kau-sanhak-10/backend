package com.ilro.backend.domain.inquiry.entity;

// 메시지 전송 상태 - 저장·전송 확인 후 SENT, 실패를 성공처럼 표시 금지 (C11·B15)
public enum MessageDeliveryStatus {
    PENDING,
    SENT,
    FAILED
}
