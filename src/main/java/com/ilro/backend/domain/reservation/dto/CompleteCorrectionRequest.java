package com.ilro.backend.domain.reservation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 실수로 한 완료 정정 (B-25, BE-13) - 사유는 예약 이력에 남음
public record CompleteCorrectionRequest(
        @NotNull(message = "필수 값입니다") Integer version,

        @NotBlank(message = "필수 값입니다") @Size(max = 255, message = "255자 이하여야 합니다")
        String reason) {
}
