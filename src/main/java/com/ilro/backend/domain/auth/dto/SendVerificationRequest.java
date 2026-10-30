package com.ilro.backend.domain.auth.dto;

import com.ilro.backend.domain.auth.entity.VerificationPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// 인증번호 발송 - phone은 010-1234-5678·01012345678 모두 받음
public record SendVerificationRequest(
        @NotNull(message = "필수 값입니다") VerificationPurpose purpose,
        @NotBlank(message = "필수 값입니다") String phone) {
}
