package com.ilro.backend.domain.notification.controller;

import com.ilro.backend.domain.notification.dto.OwnerNotificationListResponse;
import com.ilro.backend.domain.notification.service.OwnerNotificationService;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 사장님 업무 알림 (B-26, BE-14) - 사장님 토큰만 (WebConfig /api/v1/owner/**)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/owner/notifications")
public class OwnerNotificationController {

    private final OwnerNotificationService notificationService;

    // B-26 알림 목록 - 최근 알림부터, unread=true면 안 읽은 것만, page는 0부터 (size 기본 20, 최대 100)
    @GetMapping
    public ApiResponse<OwnerNotificationListResponse> getNotifications(
            AuthPrincipal principal, @RequestParam(required = false) Boolean unread,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.onSuccess(notificationService.getNotifications(principal, unread, page, size));
    }

    // B-26 읽음 처리 - 이미 읽었어도 200, 다른 업체 알림은 404
    @PostMapping("/{notificationId}/read")
    public ApiResponse<OwnerNotificationListResponse.Item> read(AuthPrincipal principal,
                                                                @PathVariable Long notificationId) {
        return ApiResponse.onSuccess(notificationService.read(principal, notificationId));
    }
}