package com.ilro.backend.domain.reservation.entity;

// 예약 상태 - 변경 진행 상태는 ReservationChangeStatus
public enum ReservationStatus {
    REQUESTED, // 요청 (사장님 확인 전)
    CONFIRMED, // 확정 (같은 업체끼리 시간 겹침 금지)
    CANCELED, // 취소
    COMPLETED // 완료
}
