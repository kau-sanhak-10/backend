package com.ilro.backend.domain.owner.dto;

import jakarta.validation.constraints.NotNull;

// 자동 예약 켜기·끄기 (B-07)
public record AutoBookingRequest(@NotNull(message = "필수 값입니다") Boolean enabled) {
}
