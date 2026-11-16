package com.ilro.backend.domain.owner.controller;

import com.ilro.backend.domain.owner.dto.OwnerMeResponse;
import com.ilro.backend.domain.owner.dto.OwnerWithdrawalRequest;
import com.ilro.backend.domain.owner.dto.OwnerWithdrawalResponse;
import com.ilro.backend.domain.owner.dto.UpdateOwnerMeRequest;
import com.ilro.backend.domain.owner.service.OwnerAccountService;
import com.ilro.backend.domain.owner.service.OwnerWithdrawalService;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 사장님 계정 (명세 4.4) - 업체는 경로가 아니라 토큰으로 정해짐
@RestController
@RequestMapping("/api/v1/owner")
@RequiredArgsConstructor
public class OwnerAccountController {

    private final OwnerAccountService ownerAccountService;
    private final OwnerWithdrawalService ownerWithdrawalService;

    @GetMapping("/me")
    public ApiResponse<OwnerMeResponse> getMe(AuthPrincipal principal) {
        return ApiResponse.onSuccess(ownerAccountService.getMe(principal.ownerId()));
    }

    @PatchMapping("/me")
    public ApiResponse<OwnerMeResponse> updateMe(AuthPrincipal principal,
                                                 @Valid @RequestBody UpdateOwnerMeRequest request) {
        return ApiResponse.onSuccess(ownerAccountService.updateOwnerName(principal.ownerId(), request.ownerName()));
    }

    // B-04 탈퇴 요청 - 로그인 문자 인증을 한 번 더 받은 verificationId 필요, 남은 문의·확정 예약이 있으면 422
    @PostMapping("/withdrawal-requests")
    public ApiResponse<OwnerWithdrawalResponse> withdraw(AuthPrincipal principal,
                                                         @Valid @RequestBody OwnerWithdrawalRequest request) {
        return ApiResponse.onSuccess(ownerWithdrawalService.withdraw(principal, request));
    }
}
