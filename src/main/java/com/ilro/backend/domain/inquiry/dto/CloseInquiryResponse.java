package com.ilro.backend.domain.inquiry.dto;

import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.entity.InquiryStatus;

// 문의 종료 (B-16) - 예약은 취소하지 않음
// reservationKept: 취소되지 않은 연결 예약이 그대로 남았으면 true (reservationId에 그 예약)
public record CloseInquiryResponse(Long inquiryId, InquiryStatus status, int version, boolean reservationKept,
                                   Long reservationId) {

    public static CloseInquiryResponse of(Inquiry inquiry, Long reservationId) {
        return new CloseInquiryResponse(inquiry.getId(), inquiry.getStatus(), inquiry.getVersion(),
                reservationId != null, reservationId);
    }
}
