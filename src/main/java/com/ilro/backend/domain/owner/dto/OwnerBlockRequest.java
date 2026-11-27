package com.ilro.backend.domain.owner.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

// 차단 시간 등록·수정 (B-22, 명세 4.6) - [startAt, endAt)
// 확정 예약과 겹치면 409 BLOCK_CONFLICTS_RESERVATION + 겹친 예약, 확인 뒤 acknowledgeConflicts: true로 다시 보내면 저장 (예약은 그대로)
// acknowledgeConflicts는 빼면 false
public record OwnerBlockRequest(@NotNull OffsetDateTime startAt, @NotNull OffsetDateTime endAt,
                                @Size(max = 100) String reason, Boolean acknowledgeConflicts) {

    public OwnerBlockRequest {
        acknowledgeConflicts = Boolean.TRUE.equals(acknowledgeConflicts);
    }
}
