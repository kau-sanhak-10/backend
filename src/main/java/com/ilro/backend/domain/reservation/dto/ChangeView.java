package com.ilro.backend.domain.reservation.dto;

import com.ilro.backend.domain.reservation.entity.ChangedBy;
import com.ilro.backend.domain.reservation.entity.RequestedBy;
import com.ilro.backend.domain.reservation.entity.ReservationChange;
import com.ilro.backend.domain.reservation.entity.ReservationChangeCloseReason;
import com.ilro.backend.domain.reservation.entity.ReservationChangeStatus;
import com.ilro.backend.global.common.util.KstTime;

import java.time.OffsetDateTime;

// 변경 요청·제안 한 건 (명세 5.3, C-17) - 고객 예약 상세의 진행 중 변경과 변경 이력이 같은 모양
// previous: 요청(업체 사유면 제안) 당시 예약 시간·주소
// proposed: 바뀔 시간·주소 - 시간은 사장님이 제안한 뒤에만, 주소는 바꿀 때만 (안 바꾸면 null)
// expiresAt: 서버가 정한 제안 기한 / resolvedAt·resolvedBy·closeReason: 끝난 변경만
public record ChangeView(Long changeId, ReservationChangeStatus status, RequestedBy requestedBy, String requestNote,
                         Slot previous, Slot proposed, OffsetDateTime expiresAt, OffsetDateTime requestedAt,
                         OffsetDateTime resolvedAt, ChangedBy resolvedBy, ReservationChangeCloseReason closeReason) {

    public record Slot(OffsetDateTime startAt, OffsetDateTime endAt, String address) {
    }

    public static ChangeView from(ReservationChange change) {
        if (change == null) {
            return null;
        }
        return new ChangeView(change.getId(), change.getStatus(), change.getRequestedBy(), change.getRequestNote(),
                new Slot(KstTime.of(change.getPrevStartAt()), KstTime.of(change.getPrevEndAt()),
                        change.getPrevAddress()),
                new Slot(KstTime.of(change.getProposedStartAt()), KstTime.of(change.getProposedEndAt()),
                        change.getProposedAddress()),
                KstTime.of(change.getExpiresAt()), KstTime.of(change.getCreatedAt()),
                KstTime.of(change.getResolvedAt()), change.getResolvedBy(), change.getCloseReason());
    }
}
