package com.ilro.backend.domain.reservation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 예약 취소 (C-16·B-20, 명세 5.4) - version은 화면이 본 예약 버전, reason은 사장님 취소면 필수
public record ReservationCancelRequest(@NotNull Integer version, @Size(max = 255) String reason) {
}
