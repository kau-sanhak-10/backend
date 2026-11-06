package com.ilro.backend.domain.inquiry.controller;

import com.ilro.backend.domain.inquiry.dto.MessagesResponse;
import com.ilro.backend.domain.inquiry.dto.OwnerHomeResponse;
import com.ilro.backend.domain.inquiry.dto.OwnerInquiryDetailResponse;
import com.ilro.backend.domain.inquiry.dto.OwnerInquiryListResponse;
import com.ilro.backend.domain.inquiry.dto.OwnerUpdateInquiryRequest;
import com.ilro.backend.domain.inquiry.entity.HandlerMode;
import com.ilro.backend.domain.inquiry.entity.InquiryStatus;
import com.ilro.backend.domain.inquiry.service.InquiryMessageService;
import com.ilro.backend.domain.inquiry.service.OwnerInquiryService;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 사장님 홈·문의 (명세 4.5, BE-07) - 자기 업체 문의만
@RestController
@RequestMapping("/api/v1/owner")
@RequiredArgsConstructor
public class OwnerInquiryController {

    private final OwnerInquiryService inquiryService;
    private final InquiryMessageService messageService;

    // B-10 오늘 방문·확인 필요 문의·대기 중 상담 요청·미확인 알림 수
    @GetMapping("/home")
    public ApiResponse<OwnerHomeResponse> getHome(AuthPrincipal principal) {
        return ApiResponse.onSuccess(inquiryService.getHome(principal));
    }

    // B-12 문의 목록 - 최근 문의부터, page는 0부터 (size 기본 20, 최대 100)
    @GetMapping("/inquiries")
    public ApiResponse<OwnerInquiryListResponse> getInquiries(
            AuthPrincipal principal, @RequestParam(required = false) InquiryStatus status,
            @RequestParam(required = false) HandlerMode handlerMode, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.onSuccess(inquiryService.getInquiries(principal, status, handlerMode, page, size));
    }

    // B-13 상세: 고객·방문정보, 항목별 값·출처·이력, AI 요약(사실·해석), 사진, 상담·회신 요청, 연결 예약
    @GetMapping("/inquiries/{inquiryId}")
    public ApiResponse<OwnerInquiryDetailResponse> getInquiry(AuthPrincipal principal,
                                                              @PathVariable Long inquiryId) {
        return ApiResponse.onSuccess(inquiryService.getInquiry(principal, inquiryId));
    }

    // B-13·B-15 대화 - afterId 다음부터
    @GetMapping("/inquiries/{inquiryId}/messages")
    public ApiResponse<MessagesResponse> getMessages(AuthPrincipal principal, @PathVariable Long inquiryId,
                                                     @RequestParam(required = false) Long afterId) {
        return ApiResponse.onSuccess(messageService.getOwnerMessages(principal, inquiryId, afterId));
    }

    // B-14 방문정보·접수 항목 수정 (출처 OWNER, 이력 보존) - 바뀐 뒤의 상세를 돌려줌
    @PatchMapping("/inquiries/{inquiryId}")
    public ApiResponse<OwnerInquiryDetailResponse> updateInquiry(AuthPrincipal principal,
                                                                 @PathVariable Long inquiryId,
                                                                 @Valid @RequestBody OwnerUpdateInquiryRequest request) {
        return ApiResponse.onSuccess(inquiryService.updateInquiry(principal, inquiryId, request));
    }
}
