package com.ilro.backend.domain.reservation.repository;

import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    // 문의에 연결된 취소되지 않은 예약 - 문의당 1건 (uq_reservation_active_inquiry)
    Optional<Reservation> findByInquiryIdAndStatusNot(Long inquiryId, ReservationStatus status);
}
