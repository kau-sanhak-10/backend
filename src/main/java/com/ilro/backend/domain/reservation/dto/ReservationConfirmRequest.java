package com.ilro.backend.domain.reservation.dto;

import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

// 고객 예약 확정 요청 (C-13, 명세 5.2) - 후보 조회에서 고른 시작 시각 (시간대 포함, 분 단위)
public record ReservationConfirmRequest(@NotNull OffsetDateTime startAt) {
}
