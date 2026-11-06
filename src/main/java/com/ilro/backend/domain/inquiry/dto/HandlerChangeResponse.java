package com.ilro.backend.domain.inquiry.dto;

import com.ilro.backend.domain.inquiry.entity.HandlerMode;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.entity.InquiryStatus;

// 직접 응대 시작·AI 응대 재개 결과 (B-15·B-16) - 바뀐 뒤의 응대 주체·단계·version
public record HandlerChangeResponse(Long inquiryId, HandlerMode handlerMode, InquiryStatus status, int version) {

    public static HandlerChangeResponse from(Inquiry inquiry) {
        return new HandlerChangeResponse(inquiry.getId(), inquiry.getHandlerMode(), inquiry.getStatus(),
                inquiry.getVersion());
    }
}
