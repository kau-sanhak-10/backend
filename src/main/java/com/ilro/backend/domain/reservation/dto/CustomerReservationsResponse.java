package com.ilro.backend.domain.reservation.dto;

import com.ilro.backend.domain.owner.entity.WorkCategory;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.global.common.util.KstTime;

import java.time.OffsetDateTime;
import java.util.List;

// 고객 예약 목록 (C-14, 명세 4.3) - 방문일이 늦은 것부터, 취소·완료 포함
// workCategory: 작업 유형 (수동 예약에서 정하지 않았으면 null)
public record CustomerReservationsResponse(List<Item> reservations) {

    public record Item(Long reservationId, ReservationStatus status, OffsetDateTime startAt, OffsetDateTime endAt,
                       WorkCategory workCategory, String address) {

        static Item from(Reservation reservation) {
            return new Item(reservation.getId(), reservation.getStatus(), KstTime.of(reservation.getStartAt()),
                    KstTime.of(reservation.getEndAt()),
                    reservation.getWorkType() == null ? null : reservation.getWorkType().getWorkCategory(),
                    reservation.getAddress());
        }
    }

    public static CustomerReservationsResponse from(List<Reservation> reservations) {
        return new CustomerReservationsResponse(reservations.stream().map(Item::from).toList());
    }
}
