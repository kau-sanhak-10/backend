package com.ilro.backend.domain.auth.controller;

import com.ilro.backend.domain.auth.dto.ConfirmVerificationRequest;
import com.ilro.backend.domain.auth.dto.ConfirmVerificationResponse;
import com.ilro.backend.domain.auth.dto.SendVerificationRequest;
import com.ilro.backend.domain.auth.dto.SendVerificationResponse;
import com.ilro.backend.domain.auth.service.AuthTokenService;
import com.ilro.backend.domain.auth.service.VerificationService;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 인증 (명세 4.1) - B-02 사장님 로그인·O-01 운영자 로그인
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final VerificationService verificationService;
    private final AuthTokenService authTokenService;

    // 인증번호 발송 - 요청 IP는 누적 발송 제한에 씀 (프록시 뒤면 server.forward-headers-strategy로 실제 IP)
    @PostMapping("/verifications")
    public ResponseEntity<ApiResponse<SendVerificationResponse>> sendVerification(
            @Valid @RequestBody SendVerificationRequest request, HttpServletRequest httpRequest) {
        SendVerificationResponse response = verificationService.send(request, httpRequest.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.onSuccess(response));
    }

    // 인증번호 확인 → 목적별 결과 (사장님·운영자 로그인이면 토큰)
    @PostMapping("/verifications/{verificationId}/confirm")
    public ApiResponse<ConfirmVerificationResponse> confirmVerification(
            @PathVariable Long verificationId, @Valid @RequestBody ConfirmVerificationRequest request) {
        return ApiResponse.onSuccess(verificationService.confirm(verificationId, request.code()));
    }

    // 지금 쓰는 토큰 철회
    @PostMapping("/logout")
    public ApiResponse<Void> logout(AuthPrincipal principal) {
        authTokenService.revoke(principal.tokenId());
        return ApiResponse.onSuccess();
    }
}
