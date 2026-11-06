package com.ilro.backend.domain.reservation.repository;

import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    // 문의에 연결된 취소되지 않은 예약 - 문의당 1건 (uq_reservation_active_inquiry)
    Optional<Reservation> findByInquiryIdAndStatusNot(Long inquiryId, ReservationStatus status);

    // 사장님 홈의 오늘 방문 (B-10) - 취소 빼고 시작 시각 순
    @Query("select r from Reservation r left join fetch r.customer left join fetch r.workType "
            + "where r.owner.id = :ownerId and r.status <> :excluded and r.startAt >= :from and r.startAt < :to "
            + "order by r.startAt, r.id")
    List<Reservation> findStartingBetween(@Param("ownerId") Long ownerId, @Param("from") Instant from,
                                          @Param("to") Instant to, @Param("excluded") ReservationStatus excluded);
}
