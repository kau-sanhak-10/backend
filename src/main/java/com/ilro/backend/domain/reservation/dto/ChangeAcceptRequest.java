package com.ilro.backend.domain.reservation.dto;

import jakarta.validation.constraints.NotNull;

// 고객 제안 수락 (C-17, 명세 5.3 3단계) - 화면이 본 예약 버전
public record ChangeAcceptRequest(@NotNull Integer baseVersion) {
}
