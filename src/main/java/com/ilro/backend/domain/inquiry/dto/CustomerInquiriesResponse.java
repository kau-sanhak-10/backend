package com.ilro.backend.domain.inquiry.dto;

import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.entity.InquiryStatus;
import com.ilro.backend.global.common.util.KstTime;

import java.time.OffsetDateTime;
import java.util.List;

// 상담 복구 목록 (C-03, BE-18) - 인증한 번호로 그 업체에 남긴 문의, 최근 것부터
// 고르는 데 필요한 것만 - 대화·방문정보는 문의를 고른 뒤 새 문의 토큰으로 봄
// symptom: 입력 전이면 null, CLOSED는 목록에 보이지만 이어갈 수 없음 (문의 토큰 발급 409)
public record CustomerInquiriesResponse(List<Item> inquiries) {

    public record Item(Long inquiryId, InquiryStatus status, String symptom, OffsetDateTime createdAt,
                       OffsetDateTime updatedAt) {

        static Item from(Inquiry inquiry) {
            return new Item(inquiry.getId(), inquiry.getStatus(), inquiry.getSymptom(),
                    KstTime.of(inquiry.getCreatedAt()), KstTime.of(inquiry.getUpdatedAt()));
        }
    }

    public static CustomerInquiriesResponse from(List<Inquiry> inquiries) {
        return new CustomerInquiriesResponse(inquiries.stream().map(Item::from).toList());
    }
}
