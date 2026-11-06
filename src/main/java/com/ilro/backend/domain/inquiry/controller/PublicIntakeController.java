package com.ilro.backend.domain.inquiry.controller;

import com.ilro.backend.domain.inquiry.dto.IntakeInfoResponse;
import com.ilro.backend.domain.inquiry.dto.StartInquiryRequest;
import com.ilro.backend.domain.inquiry.dto.StartInquiryResponse;
import com.ilro.backend.domain.inquiry.service.IntakeService;
import com.ilro.backend.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 고객 접수 링크 (명세 4.2, BE-05) - 로그인 없이 쓰는 공개 API
@RestController
@RequestMapping("/api/v1/public/intake/{linkToken}")
@RequiredArgsConstructor
public class PublicIntakeController {

    private final IntakeService intakeService;

    // C-01 업체 확인: 상호·안내 문구·제공 서비스·접수 가능 여부·약관 버전
    @GetMapping
    public ApiResponse<IntakeInfoResponse> getIntake(@PathVariable String linkToken) {
        return ApiResponse.onSuccess(intakeService.getIntake(linkToken));
    }

    // C-01·C-06 필수 동의 기록 + 문의 생성 → 문의 토큰
    @PostMapping("/inquiries")
    public ResponseEntity<ApiResponse<StartInquiryResponse>> startInquiry(
            @PathVariable String linkToken, @Valid @RequestBody StartInquiryRequest request) {
        StartInquiryResponse response = intakeService.startInquiry(linkToken, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.onSuccess(response));
    }
}
