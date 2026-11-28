package com.ilro.backend.domain.admin.controller;

import com.ilro.backend.domain.admin.dto.AccessLogListResponse;
import com.ilro.backend.domain.admin.dto.PrivacyDeletionRequest;
import com.ilro.backend.domain.admin.dto.PrivacyDeletionResponse;
import com.ilro.backend.domain.admin.service.AccessLogQueryService;
import com.ilro.backend.domain.admin.service.PrivacyDeletionService;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

// 개인정보 처리·접근 이력 (O-09, 명세 4.8·5.9, BE-19) - SUPER 운영자만
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminPrivacyController {

    private final AccessLogQueryService accessLogQueryService;
    private final PrivacyDeletionService privacyDeletionService;

    // 고객정보 접근 기록 - from·to는 한국 날짜(둘 다 포함, 한 번에 31일까지), 안 보내면 오늘까지 7일
    @GetMapping("/access-logs")
    public ApiResponse<AccessLogListResponse> accessLogs(
            AuthPrincipal principal,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.onSuccess(
                accessLogQueryService.search(principal, targetType, targetId, from, to, page, size));
    }

    // 개인정보 삭제 (문의 단위) - 처리된 범위와 실패한 범위를 나눠 돌려줌
    @PostMapping("/privacy/deletions")
    public ApiResponse<PrivacyDeletionResponse> delete(AuthPrincipal principal,
                                                       @Valid @RequestBody PrivacyDeletionRequest request) {
        return ApiResponse.onSuccess(privacyDeletionService.delete(principal, request));
    }
}
