package com.ilro.backend.domain.inquiry.dto;

import com.ilro.backend.domain.inquiry.entity.HandoffRequest;
import com.ilro.backend.domain.inquiry.entity.HandoffRequestType;
import com.ilro.backend.domain.inquiry.entity.HandoffStatus;
import com.ilro.backend.global.common.util.KstTime;

import java.time.OffsetDateTime;

// 사장님이 보는 직접 상담·전화 회신 요청 - 회신받을 이름·번호 포함 (전화 회신일 때)
public record OwnerHandoffResponse(Long handoffId, Long inquiryId, HandoffRequestType requestType, String name,
                                   String phone, HandoffStatus status, OffsetDateTime createdAt,
                                   OffsetDateTime resolvedAt) {

    public static OwnerHandoffResponse from(HandoffRequest handoff) {
        return new OwnerHandoffResponse(handoff.getId(), handoff.getInquiry().getId(), handoff.getRequestType(),
                handoff.getName(), handoff.getPhone(), handoff.getStatus(), KstTime.of(handoff.getCreatedAt()),
                KstTime.of(handoff.getResolvedAt()));
    }
}
