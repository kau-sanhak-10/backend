package com.ilro.backend.domain.reservation.dto;

import com.ilro.backend.domain.reservation.entity.ReservationChange;
import com.ilro.backend.domain.reservation.entity.ReservationChangeStatus;
import com.ilro.backend.global.common.util.KstTime;

import java.time.OffsetDateTime;

// 변경 요청 지금 상태 - 변경 API 응답과 오류 응답의 change (명세 3.5·5.3), expiresAt은 사장님이 제안한 뒤에만
public record ChangeSnapshot(Long changeId, ReservationChangeStatus status, OffsetDateTime expiresAt) {

    public static ChangeSnapshot from(ReservationChange change) {
        return new ChangeSnapshot(change.getId(), change.getStatus(), KstTime.of(change.getExpiresAt()));
    }
}
