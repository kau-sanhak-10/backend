package com.ilro.backend.domain.reservation.entity;

// 예약 변경 요청 종류 - 취소는 승인 없이 즉시 처리해 reservation_history에만 남기므로 CHANGE만 씀
public enum ReservationChangeType {
    CHANGE // 변경
}
