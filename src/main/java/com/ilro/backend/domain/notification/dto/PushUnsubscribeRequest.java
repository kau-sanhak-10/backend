package com.ilro.backend.domain.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 이 브라우저 구독 해제 (B-27) - 로그아웃할 때도 화면이 먼저 보냄
public record PushUnsubscribeRequest(
        @NotBlank(message = "필수 값입니다")
        @Size(max = 500, message = "500자 이하여야 합니다")
        String endpoint) {
}