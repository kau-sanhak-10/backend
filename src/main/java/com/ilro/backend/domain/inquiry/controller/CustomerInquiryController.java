package com.ilro.backend.domain.inquiry.controller;

import com.ilro.backend.domain.inquiry.dto.InquiryDetailResponse;
import com.ilro.backend.domain.inquiry.dto.InquiryFieldsResponse;
import com.ilro.backend.domain.inquiry.dto.MessagesResponse;
import com.ilro.backend.domain.inquiry.dto.SendMessageRequest;
import com.ilro.backend.domain.inquiry.dto.SendMessageResponse;
import com.ilro.backend.domain.inquiry.dto.UpdateFieldsRequest;
import com.ilro.backend.domain.inquiry.dto.VisitInfoRequest;
import com.ilro.backend.domain.inquiry.service.CustomerInquiryService;
import com.ilro.backend.domain.inquiry.service.InquiryFieldService;
import com.ilro.backend.domain.inquiry.service.InquiryMessageService;
import com.ilro.backend.domain.inquiry.service.InquiryMessageService.SentMessage;
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

// 고객 상담 (명세 4.2, BE-05) - 문의 토큰(inq_)으로 그 문의에만 접근
@RestController
@RequestMapping("/api/v1/customer/inquiries/{inquiryId}")
@RequiredArgsConstructor
public class CustomerInquiryController {

    private final CustomerInquiryService inquiryService;
    private final InquiryMessageService messageService;
    private final InquiryFieldService fieldService;

    // C-03·C-09 문의 상태·응대 주체·다음 안내·방문정보·연결 예약
    @GetMapping
    public ApiResponse<InquiryDetailResponse> getInquiry(AuthPrincipal principal, @PathVariable Long inquiryId) {
        return ApiResponse.onSuccess(inquiryService.getInquiry(principal, inquiryId));
    }

    // C-04·C-11 새 메시지 가져오기 - afterId 다음부터
    @GetMapping("/messages")
    public ApiResponse<MessagesResponse> getMessages(AuthPrincipal principal, @PathVariable Long inquiryId,
                                                     @RequestParam(required = false) Long afterId) {
        return ApiResponse.onSuccess(messageService.getMessages(principal, inquiryId, afterId));
    }

    // C-04·C-07·C-09 메시지 보내기 - 새로 저장 201, 같은 clientMsgId 재전송 200 + 처음 결과
    @PostMapping("/messages")
    public ResponseEntity<ApiResponse<SendMessageResponse>> sendMessage(
            AuthPrincipal principal, @PathVariable Long inquiryId, @Valid @RequestBody SendMessageRequest request) {
        SentMessage sent = messageService.sendMessage(principal, inquiryId, request);
        return ResponseEntity.status(sent.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.onSuccess(sent.response()));
    }

    // C-06 이름·전화·주소·출입 안내·작업 유형(선택)
    @PutMapping("/visit-info")
    public ApiResponse<InquiryDetailResponse> updateVisitInfo(AuthPrincipal principal, @PathVariable Long inquiryId,
                                                              @Valid @RequestBody VisitInfoRequest request) {
        return ApiResponse.onSuccess(inquiryService.updateVisitInfo(principal, inquiryId, request));
    }

    // C-08 접수정보: 항목별 값·출처·확인 여부
    @GetMapping("/fields")
    public ApiResponse<InquiryFieldsResponse> getFields(AuthPrincipal principal, @PathVariable Long inquiryId) {
        return ApiResponse.onSuccess(fieldService.getFields(principal, inquiryId));
    }

    // C-08 고객 수정 (출처 CUSTOMER)
    @PatchMapping("/fields")
    public ApiResponse<InquiryFieldsResponse> updateFields(AuthPrincipal principal, @PathVariable Long inquiryId,
                                                           @Valid @RequestBody UpdateFieldsRequest request) {
        return ApiResponse.onSuccess(fieldService.updateFields(principal, inquiryId, request));
    }
}
