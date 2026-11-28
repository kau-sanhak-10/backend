package com.ilro.backend.domain.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 신청 거절 (O-02) - 사유 필수, 사장님이 로그인할 때 이 사유를 봄 (OWNER_REJECTED의 rejectionReason)
public record RejectOwnerRequest(
        @NotBlank(message = "필수 값입니다") @Size(max = 255, message = "255자 이하여야 합니다")
        String reason) {
}
