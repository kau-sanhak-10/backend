package com.ilro.backend.domain.notification.entity;

// 알림 사건 종류 - 예약 사건은 RESERVATION_CONFIRMED 등 세분화한 값을 씀 (V1 값은 기존 행 때문에 유지)
public enum NotificationEventType {
    NEW_RESERVATION, // 새 예약
    NEEDS_CHECK, // 사장님 확인 필요
    CHANGE, // 변경
    CANCEL, // 취소
    RESULT, // 처리 결과
    RESERVATION_CONFIRMED, // 예약 확정
    CHANGE_PROPOSED, // 변경 시간 제안
    CHANGE_APPLIED, // 변경 확정
    RESERVATION_CANCELED // 예약 취소
}
