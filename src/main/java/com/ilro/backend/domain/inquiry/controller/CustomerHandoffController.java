package com.ilro.backend.domain.inquiry.controller;

import com.ilro.backend.domain.inquiry.dto.CallbackRequest;
import com.ilro.backend.domain.inquiry.dto.HandoffResponse;
import com.ilro.backend.domain.inquiry.service.CustomerHandoffService;
import com.ilro.backend.domain.inquiry.service.CustomerHandoffService.Requested;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 고객의 사람 응대 요청 (명세 4.2·5.5, BE-08) - 문의 토큰(inq_)으로 그 문의에만
// 새 요청 201, 진행 중 요청을 돌려주거나 대기 중 직접 상담을 전화 회신으로 바꾸면 200
@RestController
@RequestMapping("/api/v1/customer/inquiries/{inquiryId}")
@RequiredArgsConstructor
public class CustomerHandoffController {

    private final CustomerHandoffService handoffService;

    // C-10 직접 상담 요청
    @PostMapping("/handoff-requests")
    public ResponseEntity<ApiResponse<HandoffResponse>> requestDirectChat(AuthPrincipal principal,
                                                                          @PathVariable Long inquiryId) {
        return respond(handoffService.requestDirectChat(principal, inquiryId));
    }

    // C-18 전화 회신 요청 - 회신받을 이름·번호
    @PostMapping("/callback-requests")
    public ResponseEntity<ApiResponse<HandoffResponse>> requestCallback(
            AuthPrincipal principal, @PathVariable Long inquiryId, @Valid @RequestBody CallbackRequest request) {
        return respond(handoffService.requestCallback(principal, inquiryId, request));
    }

    private static ResponseEntity<ApiResponse<HandoffResponse>> respond(Requested requested) {
        return ResponseEntity.status(requested.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.onSuccess(requested.handoff()));
    }
}
