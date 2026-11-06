package com.ilro.backend.domain.owner.controller;

import com.ilro.backend.domain.owner.dto.IntakeLinkResponse;
import com.ilro.backend.domain.owner.service.IntakeLinkService;
import com.ilro.backend.global.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// O-04 접수 링크 발급·재발급 - 기존 링크는 폐기됨
@RestController
@RequestMapping("/api/v1/admin/owners/{ownerId}/intake-link")
@RequiredArgsConstructor
public class AdminIntakeLinkController {

    private final IntakeLinkService intakeLinkService;

    @PostMapping
    public ApiResponse<IntakeLinkResponse> issueIntakeLink(@PathVariable Long ownerId) {
        return ApiResponse.onSuccess(intakeLinkService.issue(ownerId));
    }
}