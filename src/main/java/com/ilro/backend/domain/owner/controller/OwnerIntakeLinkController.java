package com.ilro.backend.domain.owner.controller;

import com.ilro.backend.domain.owner.dto.IntakeLinkResponse;
import com.ilro.backend.domain.owner.service.IntakeLinkService;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// B-11 접수 링크 공유 - 업체는 경로가 아니라 토큰으로 정해짐
@RestController
@RequestMapping("/api/v1/owner/intake-link")
@RequiredArgsConstructor
public class OwnerIntakeLinkController {

    private final IntakeLinkService intakeLinkService;

    @GetMapping
    public ApiResponse<IntakeLinkResponse> getIntakeLink(AuthPrincipal principal) {
        return ApiResponse.onSuccess(intakeLinkService.getLink(principal.ownerId()));
    }
}