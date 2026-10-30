package com.ilro.backend.domain.auth.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ConfirmVerificationRequest(
        @NotNull(message = "필수 값입니다")
        @Pattern(regexp = "\\d{6}", message = "6자리 숫자여야 합니다")
        String code) {
}
