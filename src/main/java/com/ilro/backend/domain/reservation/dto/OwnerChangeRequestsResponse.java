package com.ilro.backend.domain.reservation.dto;

import com.ilro.backend.domain.reservation.entity.RequestedBy;
import com.ilro.backend.domain.reservation.entity.ReservationChange;
import com.ilro.backend.domain.reservation.entity.ReservationChangeStatus;
import com.ilro.backend.global.common.util.KstTime;

import java.time.OffsetDateTime;
import java.util.List;

// 사장님 변경 요청·제안 대기 목록 (B-21) - 최근 순
// prev*: 요청 당시 예약, proposed*: 사장님이 제안한 시각 / 고객이 요청한 주소 (없으면 null)
public record OwnerChangeRequestsResponse(List<Item> changes) {

    public record Item(Long changeId, Long reservationId, ReservationChangeStatus status, RequestedBy requestedBy,
                       String requestNote, OffsetDateTime prevStartAt, OffsetDateTime prevEndAt, String prevAddress,
                       OffsetDateTime proposedStartAt, OffsetDateTime proposedEndAt, String proposedAddress,
                       OffsetDateTime expiresAt, OffsetDateTime createdAt, String customerName,
                       String customerPhone) {

        static Item from(ReservationChange change) {
            var customer = change.getReservation().getCustomer();
            return new Item(change.getId(), change.getReservation().getId(), change.getStatus(),
                    change.getRequestedBy(), change.getRequestNote(), KstTime.of(change.getPrevStartAt()),
                    KstTime.of(change.getPrevEndAt()), change.getPrevAddress(),
                    KstTime.of(change.getProposedStartAt()), KstTime.of(change.getProposedEndAt()),
                    change.getProposedAddress(), KstTime.of(change.getExpiresAt()), KstTime.of(change.getCreatedAt()),
                    customer == null ? null : customer.getName(), customer == null ? null : customer.getPhone());
        }
    }

    public static OwnerChangeRequestsResponse from(List<ReservationChange> changes) {
        return new OwnerChangeRequestsResponse(changes.stream().map(Item::from).toList());
    }
}
