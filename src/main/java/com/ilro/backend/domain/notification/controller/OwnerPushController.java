package com.ilro.backend.domain.notification.controller;

import com.ilro.backend.domain.notification.dto.PushPublicKeyResponse;
import com.ilro.backend.domain.notification.dto.PushSubscriptionRequest;
import com.ilro.backend.domain.notification.dto.PushUnsubscribeRequest;
import com.ilro.backend.domain.notification.service.PushSubscriptionService;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ilro.backend.domain.notification.entity.NotifySettingType;
import com.ilro.backend.domain.notification.service.NotificationSettingsService;
import org.springframework.web.bind.annotation.PutMapping;
import java.util.Map;

// 사장님 브라우저 Web Push (B-27, 명세 5.6, BE-14) - 사장님 토큰만 (WebConfig /api/v1/owner/**)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/owner")
public class OwnerPushController {

    private final PushSubscriptionService pushSubscriptionService;
    private final NotificationSettingsService settingsService;

    // B-27 공개키 - 브라우저가 구독을 만들 때 씀, 서버에 키가 없으면 503 PUSH_UNAVAILABLE
    @GetMapping("/push/public-key")
    public ApiResponse<PushPublicKeyResponse> publicKey() {
        return ApiResponse.onSuccess(pushSubscriptionService.publicKey());
    }

    // B-27 이 브라우저 구독 등록 - 같은 endpoint면 갱신·이 사장님으로 옮김, 푸시 서버 주소가 아니면 400
    @PostMapping("/push-subscriptions")
    public ApiResponse<Void> subscribe(AuthPrincipal principal, @Valid @RequestBody PushSubscriptionRequest request) {
        pushSubscriptionService.subscribe(principal, request);
        return ApiResponse.onSuccess();
    }

    // B-27 이 브라우저 구독 해제 (로그아웃 때도) - 없어도 200
    @DeleteMapping("/push-subscriptions")
    public ApiResponse<Void> unsubscribe(AuthPrincipal principal,
                                         @Valid @RequestBody PushUnsubscribeRequest request) {
        pushSubscriptionService.unsubscribe(principal, request);
        return ApiResponse.onSuccess();
    }

    // B-27 알림 유형별 푸시 수신 여부 - 저장 전이면 모두 켜짐
    @GetMapping("/notification-settings")
    public ApiResponse<Map<NotifySettingType, Boolean>> getSettings(AuthPrincipal principal) {
        return ApiResponse.onSuccess(settingsService.get(principal));
    }

    // B-27 4개 유형을 모두 보냄 - 빠지거나 null이면 400, 모르는 키도 400. 꺼도 웹 안 알림 목록은 그대로
    @PutMapping("/notification-settings")
    public ApiResponse<Map<NotifySettingType, Boolean>> updateSettings(
            AuthPrincipal principal, @RequestBody Map<NotifySettingType, Boolean> request) {
        return ApiResponse.onSuccess(settingsService.update(principal, request));
    }
}