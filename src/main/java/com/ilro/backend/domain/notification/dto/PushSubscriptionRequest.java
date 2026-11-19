package com.ilro.backend.domain.notification.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 브라우저 푸시 구독 등록 (B-27, 명세 5.6) - 브라우저 PushSubscription.toJSON() 모양 그대로
// endpoint가 알려진 푸시 서버 주소인지는 서비스에서 확인 (서버가 나중에 이 주소로 요청을 보내므로)
public record PushSubscriptionRequest(
        @NotBlank(message = "필수 값입니다")
        @Size(max = 500, message = "500자 이하여야 합니다")
        String endpoint,

        @NotNull(message = "필수 값입니다")
        @Valid
        Keys keys,

        // 사장님이 기기를 구분하는 이름 (예: "Chrome / Android") - 길면 서버가 잘라 저장
        String userAgent) {

    // 브라우저가 만든 암호화 키 (base64url)
    public record Keys(
            @NotBlank(message = "필수 값입니다")
            @Size(max = 255, message = "255자 이하여야 합니다")
            @Pattern(regexp = "[A-Za-z0-9_-]+={0,2}", message = "base64url 형식이어야 합니다")
            String p256dh,

            @NotBlank(message = "필수 값입니다")
            @Size(max = 255, message = "255자 이하여야 합니다")
            @Pattern(regexp = "[A-Za-z0-9_-]+={0,2}", message = "base64url 형식이어야 합니다")
            String auth) {
    }
}