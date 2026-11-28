package com.ilro.backend.domain.admin.dto;

import com.ilro.backend.domain.owner.entity.ApprovalStatus;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.entity.OwnerStatus;
import com.ilro.backend.global.common.util.KstTime;
import org.springframework.data.domain.Page;

import java.time.OffsetDateTime;
import java.util.List;

// 운영 업체 목록 (O-01) - 최근 등록부터
// 목록 공통 형식 (명세 1.2) - page는 0부터, isLast가 아니면 page+1로 다음 쪽을 요청
public record AdminOwnerListResponse(List<Item> content, int page, int size, long totalElements, int totalPages,
                                     boolean isLast) {

    public record Item(Long ownerId, String businessName, String ownerName, String phone,
                       ApprovalStatus approvalStatus, OwnerStatus status, OffsetDateTime createdAt,
                       OffsetDateTime reviewedAt) {

        static Item from(Owner owner) {
            return new Item(owner.getId(), owner.getBusinessName(), owner.getOwnerName(), owner.getPhone(),
                    owner.getApprovalStatus(), owner.getStatus(), KstTime.of(owner.getCreatedAt()),
                    KstTime.of(owner.getReviewedAt()));
        }
    }

    public static AdminOwnerListResponse from(Page<Owner> found) {
        return new AdminOwnerListResponse(found.getContent().stream().map(Item::from).toList(), found.getNumber(),
                found.getSize(), found.getTotalElements(), found.getTotalPages(), found.isLast());
    }
}
