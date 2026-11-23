package com.ilro.backend.domain.reservation.repository;

import com.ilro.backend.domain.reservation.entity.ReservationHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReservationHistoryRepository extends JpaRepository<ReservationHistory, Long> {

    List<ReservationHistory> findByReservationIdOrderByIdAsc(Long reservationId);
}
