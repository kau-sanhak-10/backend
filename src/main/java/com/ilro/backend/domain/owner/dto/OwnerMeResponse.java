package com.ilro.backend.domain.owner.dto;

import com.ilro.backend.domain.owner.entity.ApprovalStatus;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.entity.OwnerStatus;

// 내 정보·업체 승인·운영 상태 (B-02)
public record OwnerMeResponse(Long ownerId, String businessName, String ownerName, String phone,
                              ApprovalStatus approvalStatus, OwnerStatus status, boolean autoBookingEnabled,
                              boolean intakePaused) {

    public static OwnerMeResponse from(Owner owner) {
        return new OwnerMeResponse(owner.getId(), owner.getBusinessName(), owner.getOwnerName(), owner.getPhone(),
                owner.getApprovalStatus(), owner.getStatus(), owner.isAutoBookingEnabled(), owner.isIntakePaused());
    }
}
