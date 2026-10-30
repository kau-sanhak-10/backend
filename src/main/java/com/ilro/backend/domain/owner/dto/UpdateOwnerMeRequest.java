package com.ilro.backend.domain.owner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 내 정보 수정 (B-03) - 이름만, 로그인 번호 변경은 운영팀 요청 (O-03)
public record UpdateOwnerMeRequest(
        @NotBlank(message = "필수 값입니다") @Size(max = 50, message = "50자 이하여야 합니다") String ownerName) {
}
