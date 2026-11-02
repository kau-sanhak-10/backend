package com.ilro.backend.domain.notification.entity;

// 알림 발송 상태 - 실패 시 재시도 대상
public enum NotificationSendStatus {
    PENDING,
    SENT,
    FAILED,
    SKIPPED // 재시도 직전에 보니 보낼 필요가 없어짐 (취소된 예약의 확정 알림 등)
}
