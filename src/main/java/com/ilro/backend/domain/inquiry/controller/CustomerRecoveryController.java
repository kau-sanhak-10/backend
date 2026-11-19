package com.ilro.backend.domain.inquiry.controller;

import com.ilro.backend.domain.inquiry.dto.CustomerInquiriesResponse;
import com.ilro.backend.domain.inquiry.dto.InquiryAccessTokenResponse;
import com.ilro.backend.domain.inquiry.service.InquiryRecoveryService;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 기존 상담 이어가기 (C-03, 명세 4.1, BE-18) - 상담 복구 고객 토큰(cus_)으로
// 흐름: 번호 인증(INQUIRY_ACCESS, linkToken) → 고객 토큰 → 내 문의 목록 → 고른 문의의 새 문의 토큰(inq_)
@RestController
@RequestMapping("/api/v1/customer/inquiries")
@RequiredArgsConstructor
public class CustomerRecoveryController {

    private final InquiryRecoveryService recoveryService;

    // 이 번호·이 업체의 문의 목록 (최근 것부터)
    @GetMapping
    public ApiResponse<CustomerInquiriesResponse> getInquiries(AuthPrincipal principal) {
        return ApiResponse.onSuccess(recoveryService.list(principal));
    }

    // 고른 문의의 새 문의 토큰 - 이 기기용으로 새로 발급 (201)
    @PostMapping("/{inquiryId}/access-token")
    public ResponseEntity<ApiResponse<InquiryAccessTokenResponse>> issueAccessToken(AuthPrincipal principal,
                                                                                    @PathVariable Long inquiryId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.onSuccess(recoveryService.issueAccessToken(principal, inquiryId)));
    }
}
