package com.ilro.backend.domain.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// 사장님 로그인 번호 변경 (O-03) - 새 번호로 받은 OWNER_SIGNUP 인증이 있어야 함
public record ChangeLoginPhoneRequest(
        @NotBlank(message = "필수 값입니다") String newPhone,
        @NotNull(message = "필수 값입니다") Long phoneVerificationId) {
}
