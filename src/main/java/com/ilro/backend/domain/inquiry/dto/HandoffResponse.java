package com.ilro.backend.domain.inquiry.dto;

import com.ilro.backend.domain.inquiry.entity.HandoffRequest;
import com.ilro.backend.domain.inquiry.entity.HandoffRequestType;
import com.ilro.backend.domain.inquiry.entity.HandoffStatus;
import com.ilro.backend.global.common.util.KstTime;

import java.time.OffsetDateTime;

// 고객이 보는 직접 상담·전화 회신 요청 (C-10·C-18) - 회신 번호는 돌려주지 않음
// 화면은 "요청이 전달됐어요"까지만 안내 (바로 연락이 온다고 약속하지 않음)
public record HandoffResponse(Long handoffId, HandoffRequestType requestType, HandoffStatus status,
                              OffsetDateTime createdAt) {

    public static HandoffResponse from(HandoffRequest handoff) {
        return new HandoffResponse(handoff.getId(), handoff.getRequestType(), handoff.getStatus(),
                KstTime.of(handoff.getCreatedAt()));
    }
}
