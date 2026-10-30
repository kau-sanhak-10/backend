package com.ilro.backend.domain.owner.dto;

import jakarta.validation.constraints.NotNull;

// 신규 접수 중지·재개 (B-09) - 진행 중인 문의·예약은 그대로
public record IntakeRequest(@NotNull(message = "필수 값입니다") Boolean intakePaused) {
}
