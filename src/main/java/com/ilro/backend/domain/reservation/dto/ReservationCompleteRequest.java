package com.ilro.backend.domain.reservation.dto;

import jakarta.validation.constraints.NotNull;

// 작업 완료 (B-25, BE-13) - version은 화면이 본 예약 버전
public record ReservationCompleteRequest(@NotNull(message = "필수 값입니다") Integer version) {
}
