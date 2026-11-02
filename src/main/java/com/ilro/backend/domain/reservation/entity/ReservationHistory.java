package com.ilro.backend.domain.reservation.entity;

import com.ilro.backend.global.entity.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 예약 상태 변경 이력
@Getter
@Entity
@Table(name = "reservation_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationHistory extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    // 처음 만들 때는 null
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ReservationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChangedBy changedBy;

    // 완료 정정(B25)·업체 사유 취소(B20) 시 기록
    private String reason;

    @Builder
    private ReservationHistory(Reservation reservation, ReservationStatus fromStatus, ReservationStatus toStatus,
                               ChangedBy changedBy, String reason) {
        this.reservation = reservation;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.changedBy = changedBy;
        this.reason = reason;
    }
}
