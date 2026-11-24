package com.ilro.backend.domain.reservation.repository;

import com.ilro.backend.domain.reservation.entity.ReservationHistory;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReservationHistoryRepository extends JpaRepository<ReservationHistory, Long> {

    List<ReservationHistory> findByReservationIdOrderByIdAsc(Long reservationId);

    // 그 상태로 바뀐 마지막 이력 - 완료 시각 (B-23)
    Optional<ReservationHistory> findFirstByReservationIdAndToStatusOrderByIdDesc(Long reservationId,
                                                                                  ReservationStatus toStatus);
}
