package com.ilro.backend.domain.reservation.repository;

import com.ilro.backend.domain.reservation.entity.ReservationChange;
import com.ilro.backend.domain.reservation.entity.ReservationChangeStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;

public interface ReservationChangeRepository extends JpaRepository<ReservationChange, Long> {

    // 진행 중 변경(REQUESTED·PROPOSED·ACCEPTED) - 예약당 1건 (uq_reservation_change_active)
    Optional<ReservationChange> findFirstByReservationIdAndStatusIn(Long reservationId,
                                                                   Collection<ReservationChangeStatus> statuses);
}
