package com.ilro.backend.domain.owner.dto;

import com.ilro.backend.domain.owner.entity.OwnerBlock;
import com.ilro.backend.global.common.util.KstTime;

import java.time.OffsetDateTime;

// 차단 시간 (B-22)
public record OwnerBlockResponse(Long blockId, OffsetDateTime startAt, OffsetDateTime endAt, String reason) {

    public static OwnerBlockResponse from(OwnerBlock block) {
        return new OwnerBlockResponse(block.getId(), KstTime.of(block.getStartAt()), KstTime.of(block.getEndAt()),
                block.getReason());
    }
}
