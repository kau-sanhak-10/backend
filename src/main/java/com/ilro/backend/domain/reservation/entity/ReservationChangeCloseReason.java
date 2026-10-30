package com.ilro.backend.domain.reservation.entity;

// 진행 중 변경 요청을 CLOSED로 끝낸 이유
public enum ReservationChangeCloseReason {
    RESERVATION_CANCELED, // 예약이 취소됨
    OWNER_CLOSED // 사장님이 요청을 닫음 (기존 예약 유지)
}
