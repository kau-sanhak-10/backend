package com.ilro.backend.domain.notification.entity;

// 사장님이 켜고 끄는 푸시 알림 유형 (B-27, 명세 5.6) - owner.notify_settings의 키
// 꺼도 웹 안 알림 목록(B-26)에는 남고 푸시만 보내지 않음
// 알림 사건(NotificationEventType)과 이름이 다름 - 발송기(3단계)에서 사건 → 유형으로 묶어서 확인
public enum NotifySettingType {

    // 새 예약 (고객 예약 확정)
    NEW_RESERVATION,
    // 확인 필요 (AI 판단·직접 상담·전화 회신 요청)
    NEEDS_CHECK,
    // 예약 변경 (고객 변경 요청·변경 확정)
    CHANGE,
    // 예약 취소 (고객 취소)
    CANCEL
}