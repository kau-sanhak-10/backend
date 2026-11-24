package com.ilro.backend.domain.reservation.dto;

import com.ilro.backend.domain.reservation.entity.ReservationStatus;

// 완료 정정 결과 (B-25, BE-13) - 확정으로 돌아간 예약과 바뀐 버전
public record CompleteCorrectionResponse(Long reservationId, ReservationStatus status, Integer version) {
}
