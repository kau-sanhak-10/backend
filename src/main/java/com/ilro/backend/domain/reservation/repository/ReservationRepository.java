package com.ilro.backend.domain.reservation.repository;

import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    // 문의에 연결된 취소되지 않은 예약 - 문의당 1건 (uq_reservation_active_inquiry)
    Optional<Reservation> findByInquiryIdAndStatusNot(Long inquiryId, ReservationStatus status);

    // 예약 확정 요청 키 (명세 1.6) - 같은 키가 다시 오면 지문을 비교해 처음 결과 또는 409
    Optional<Reservation> findByIdempotencyKey(String idempotencyKey);

    // 사장님 예약 - 다른 업체 예약은 없는 것처럼 (BE-12)
    Optional<Reservation> findByIdAndOwnerId(Long id, Long ownerId);

    // 사장님 탈퇴 확인 (B-04, BE-16) - 끝나지 않은 예약 수 (지난 방문도 완료 처리 전이면 셈)
    long countByOwnerIdAndStatusIn(Long ownerId, Collection<ReservationStatus> statuses);

    // 차단 시간과 겹치는 확정 예약 (B-22)
    @Query("select r from Reservation r where r.owner.id = :ownerId and r.status = :status "
            + "and r.startAt < :to and r.endAt > :from order by r.startAt, r.id")
    List<Reservation> findOverlappingWithStatus(@Param("ownerId") Long ownerId, @Param("from") Instant from,
                                                @Param("to") Instant to, @Param("status") ReservationStatus status);

    // 고객 예약 조회 (C-14, BE-11) - 그 업체에서 이 번호로 잡은 예약, 방문일이 늦은 것부터 (취소·완료 포함)
    @Query("select r from Reservation r join fetch r.customer c left join fetch r.workType "
            + "where r.owner.id = :ownerId and c.phone = :phone order by r.startAt desc, r.id desc")
    List<Reservation> findByCustomerPhone(@Param("ownerId") Long ownerId, @Param("phone") String phone,
                                          Pageable pageable);

    // 문의 토큰으로 보는 예약 (명세 4.3) - 그 문의에서 잡은 예약만
    @Query("select r from Reservation r left join fetch r.workType where r.inquiry.id = :inquiryId "
            + "order by r.startAt desc, r.id desc")
    List<Reservation> findByInquiry(@Param("inquiryId") Long inquiryId);

    // 사장님 홈의 오늘 방문 (B-10) - 취소 빼고 시작 시각 순
    @Query("select r from Reservation r left join fetch r.customer left join fetch r.workType "
            + "where r.owner.id = :ownerId and r.status <> :excluded and r.startAt >= :from and r.startAt < :to "
            + "order by r.startAt, r.id")
    List<Reservation> findStartingBetween(@Param("ownerId") Long ownerId, @Param("from") Instant from,
                                          @Param("to") Instant to, @Param("excluded") ReservationStatus excluded);

    // 기간과 겹치는 예약 (취소 제외) - 방문 가능 시간 계산의 기존 방문 (명세 5.2)
    @Query("select r from Reservation r where r.owner.id = :ownerId and r.status <> :excluded "
            + "and r.startAt < :to and r.endAt > :from order by r.startAt, r.id")
    List<Reservation> findOverlapping(@Param("ownerId") Long ownerId, @Param("from") Instant from,
                                      @Param("to") Instant to, @Param("excluded") ReservationStatus excluded);
}
