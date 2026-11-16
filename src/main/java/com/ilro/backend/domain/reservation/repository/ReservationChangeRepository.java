package com.ilro.backend.domain.reservation.repository;

import com.ilro.backend.domain.reservation.entity.ReservationChange;
import com.ilro.backend.domain.reservation.entity.ReservationChangeStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReservationChangeRepository extends JpaRepository<ReservationChange, Long> {

    // 진행 중 변경(REQUESTED·PROPOSED·ACCEPTED) - 예약당 1건 (uq_reservation_change_active)
    Optional<ReservationChange> findFirstByReservationIdAndStatusIn(Long reservationId,
                                                                   Collection<ReservationChangeStatus> statuses);

    List<ReservationChange> findByReservationIdAndStatusIn(Long reservationId,
                                                           Collection<ReservationChangeStatus> statuses);

    // 사장님 일정 (B-17) - 보이는 예약들의 진행 중 변경을 한 번에
    List<ReservationChange> findByReservationIdInAndStatusIn(Collection<Long> reservationIds,
                                                             Collection<ReservationChangeStatus> statuses);

    // 고객 변경 이력 (C-17) - 그 예약의 변경, 최근 순
    List<ReservationChange> findByReservationIdOrderByCreatedAtDescIdDesc(Long reservationId, Pageable pageable);

    // 기한이 지난 제안 (명세 5.3 만료, BE-16) - 오래 지난 것부터, 업체 잠금을 위해 예약·업체를 같이 읽음
    @Query("select c from ReservationChange c join fetch c.reservation r "
            + "where c.status = com.ilro.backend.domain.reservation.entity.ReservationChangeStatus.PROPOSED "
            + "and c.expiresAt <= :now order by c.expiresAt, c.id")
    List<ReservationChange> findDueProposals(@Param("now") Instant now, Pageable pageable);

    // 사장님 변경 요청 목록 (B-21) - 자기 업체 예약의 변경, 최근 순
    @Query("select c from ReservationChange c join fetch c.reservation r left join fetch r.customer "
            + "where r.owner.id = :ownerId and c.status in :statuses order by c.createdAt desc, c.id desc")
    List<ReservationChange> findByOwner(@Param("ownerId") Long ownerId,
                                        @Param("statuses") Collection<ReservationChangeStatus> statuses,
                                        Pageable pageable);
}
