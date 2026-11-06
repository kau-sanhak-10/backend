package com.ilro.backend.domain.inquiry.controller;

import com.ilro.backend.domain.inquiry.dto.CloseInquiryResponse;
import com.ilro.backend.domain.inquiry.dto.HandlerChangeResponse;
import com.ilro.backend.domain.inquiry.dto.MessagesResponse.MessageItem;
import com.ilro.backend.domain.inquiry.dto.OwnerHandoffResponse;
import com.ilro.backend.domain.inquiry.dto.OwnerMessageRequest;
import com.ilro.backend.domain.inquiry.dto.UpdateHandoffRequest;
import com.ilro.backend.domain.inquiry.service.OwnerHandlingService;
import com.ilro.backend.domain.inquiry.service.OwnerHandlingService.SentMessage;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 사장님 응대 전환 (명세 4.5, BE-08) - 직접 응대·답변·AI 재개·종료·상담 요청 처리
@RestController
@RequestMapping("/api/v1/owner")
@RequiredArgsConstructor
public class OwnerHandlingController {

    private final OwnerHandlingService handlingService;

    // B-15 직접 응대 시작 (AI 답변·자동 예약 중지)
    @PostMapping("/inquiries/{inquiryId}/takeover")
    public ApiResponse<HandlerChangeResponse> takeOver(AuthPrincipal principal, @PathVariable Long inquiryId) {
        return ApiResponse.onSuccess(handlingService.takeOver(principal, inquiryId));
    }

    // B-15 사장님 답변 - 새로 저장 201, 같은 clientMsgId 재전송 200 + 처음 결과
    @PostMapping("/inquiries/{inquiryId}/messages")
    public ResponseEntity<ApiResponse<MessageItem>> sendMessage(
            AuthPrincipal principal, @PathVariable Long inquiryId, @Valid @RequestBody OwnerMessageRequest request) {
        SentMessage sent = handlingService.sendMessage(principal, inquiryId, request);
        return ResponseEntity.status(sent.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.onSuccess(sent.message()));
    }

    // B-16 AI 응대 재개 - 안전·필수 정보 미해결이면 422 AI_RESUME_BLOCKED
    @PostMapping("/inquiries/{inquiryId}/resume-ai")
    public ApiResponse<HandlerChangeResponse> resumeAi(AuthPrincipal principal, @PathVariable Long inquiryId) {
        return ApiResponse.onSuccess(handlingService.resumeAi(principal, inquiryId));
    }

    // B-16 문의 종료 (예약은 유지)
    @PostMapping("/inquiries/{inquiryId}/close")
    public ApiResponse<CloseInquiryResponse> close(AuthPrincipal principal, @PathVariable Long inquiryId) {
        return ApiResponse.onSuccess(handlingService.close(principal, inquiryId));
    }

    // B-15·C-18 직접 상담·전화 회신 요청 처리 상태 (IN_PROGRESS·DONE)
    @PatchMapping("/handoff-requests/{handoffId}")
    public ApiResponse<OwnerHandoffResponse> updateHandoff(AuthPrincipal principal, @PathVariable Long handoffId,
                                                           @Valid @RequestBody UpdateHandoffRequest request) {
        return ApiResponse.onSuccess(handlingService.updateHandoff(principal, handoffId, request));
    }
}
