package com.ilro.backend.domain.admin.dto;

import com.ilro.backend.domain.admin.entity.Admin;
import com.ilro.backend.domain.owner.entity.ApprovalStatus;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.entity.OwnerStatus;
import com.ilro.backend.global.common.util.KstTime;

import java.time.OffsetDateTime;

// 운영 업체 상세 (O-01) - 업체·계정 상태만. 그 업체의 고객·예약·문의 내용은 담지 않음 (명세 5.8)
// approvalStatus(신청 심사)와 status(운영 상태)는 따로 - status는 APPROVED일 때만 의미가 있음
// intakeLinkActive: 고객 접수 링크가 살아 있는지 (승인됐고 탈퇴가 아님) - 링크 주소는 발급 API(O-04)가 돌려줌
// reviewedBy: 심사한 운영자 (심사 전이면 null)
public record AdminOwnerResponse(Long ownerId, String businessName, String ownerName, String phone,
                                 String contactPhone, String address, String serviceDescription,
                                 ApprovalStatus approvalStatus, OwnerStatus status, String rejectionReason,
                                 OffsetDateTime reviewedAt, Reviewer reviewedBy, boolean autoBookingEnabled,
                                 boolean intakePaused, boolean intakeLinkActive, OffsetDateTime createdAt,
                                 OffsetDateTime updatedAt) {

    public record Reviewer(Long adminId, String name) {
    }

    public static AdminOwnerResponse from(Owner owner) {
        Admin reviewer = owner.getReviewedBy();
        return new AdminOwnerResponse(owner.getId(), owner.getBusinessName(), owner.getOwnerName(), owner.getPhone(),
                owner.getContactPhone(), owner.getAddress(), owner.getServiceDescription(), owner.getApprovalStatus(),
                owner.getStatus(), owner.getRejectionReason(), KstTime.of(owner.getReviewedAt()),
                reviewer == null ? null : new Reviewer(reviewer.getId(), reviewer.getName()),
                owner.isAutoBookingEnabled(), owner.isIntakePaused(), owner.isIntakeLinkValid(),
                KstTime.of(owner.getCreatedAt()), KstTime.of(owner.getUpdatedAt()));
    }
}
