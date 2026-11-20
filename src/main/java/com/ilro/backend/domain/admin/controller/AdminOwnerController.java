package com.ilro.backend.domain.admin.controller;

import com.ilro.backend.domain.admin.dto.AdminOwnerListResponse;
import com.ilro.backend.domain.admin.dto.AdminOwnerResponse;
import com.ilro.backend.domain.admin.dto.ChangeLoginPhoneRequest;
import com.ilro.backend.domain.admin.dto.ChangeOwnerStatusRequest;
import com.ilro.backend.domain.admin.dto.RegisterOwnerRequest;
import com.ilro.backend.domain.admin.dto.RejectOwnerRequest;
import com.ilro.backend.domain.admin.service.AdminOwnerService;
import com.ilro.backend.domain.owner.entity.ApprovalStatus;
import com.ilro.backend.domain.owner.entity.OwnerStatus;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 운영 업체·계정 관리 (명세 4.8·5.8, BE-17) - 운영자 토큰만 (WebConfig)
// 접수 링크 발급·재발급(O-04)은 AdminIntakeLinkController
@RestController
@RequestMapping("/api/v1/admin/owners")
@RequiredArgsConstructor
public class AdminOwnerController {

    private final AdminOwnerService adminOwnerService;

    // B-01·O-02 업체 신청 등록 → 승인 대기
    @PostMapping
    public ResponseEntity<ApiResponse<AdminOwnerResponse>> register(AuthPrincipal principal,
                                                                    @Valid @RequestBody RegisterOwnerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.onSuccess(adminOwnerService.register(principal, request)));
    }

    // O-01 업체·계정 상태 조회 - 최근 등록부터, page는 0부터 (size 기본 20, 최대 100)
    @GetMapping
    public ApiResponse<AdminOwnerListResponse> list(AuthPrincipal principal,
                                                    @RequestParam(required = false) ApprovalStatus approvalStatus,
                                                    @RequestParam(required = false) OwnerStatus status,
                                                    @RequestParam(required = false) String q,
                                                    @RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size) {
        return ApiResponse.onSuccess(adminOwnerService.list(principal, approvalStatus, status, q, page, size));
    }

    // O-01 업체 상세
    @GetMapping("/{ownerId}")
    public ApiResponse<AdminOwnerResponse> get(AuthPrincipal principal, @PathVariable Long ownerId) {
        return ApiResponse.onSuccess(adminOwnerService.get(principal, ownerId));
    }

    // O-02 승인
    @PostMapping("/{ownerId}/approve")
    public ApiResponse<AdminOwnerResponse> approve(AuthPrincipal principal, @PathVariable Long ownerId) {
        return ApiResponse.onSuccess(adminOwnerService.approve(principal, ownerId));
    }

    // O-02 거절 (사유 필수)
    @PostMapping("/{ownerId}/reject")
    public ApiResponse<AdminOwnerResponse> reject(AuthPrincipal principal, @PathVariable Long ownerId,
                                                  @Valid @RequestBody RejectOwnerRequest request) {
        return ApiResponse.onSuccess(adminOwnerService.reject(principal, ownerId, request));
    }

    // O-02 운영 상태 READY·ACTIVE·RESTRICTED
    @PatchMapping("/{ownerId}/status")
    public ApiResponse<AdminOwnerResponse> changeStatus(AuthPrincipal principal, @PathVariable Long ownerId,
                                                        @Valid @RequestBody ChangeOwnerStatusRequest request) {
        return ApiResponse.onSuccess(adminOwnerService.changeStatus(principal, ownerId, request));
    }

    // O-03 로그인 번호 변경 (SUPER 운영자) - 기존 사장님 로그인은 모두 끊김
    @PutMapping("/{ownerId}/login-phone")
    public ApiResponse<AdminOwnerResponse> changeLoginPhone(AuthPrincipal principal, @PathVariable Long ownerId,
                                                            @Valid @RequestBody ChangeLoginPhoneRequest request) {
        return ApiResponse.onSuccess(adminOwnerService.changeLoginPhone(principal, ownerId, request));
    }
}
