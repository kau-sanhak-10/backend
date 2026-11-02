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

import java.math.BigDecimal;
import java.time.Instant;

// 예약 변경 요청 - APPLIED 전까지 기존 예약 유지, 진행 중(REQUESTED·PROPOSED·ACCEPTED) 요청은 예약당 1건
@Getter
@Entity
@Table(name = "reservation_change")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationChange extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationChangeType changeType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequestedBy requestedBy;

    // 고객 요청 내용 (희망 날짜·시간대 등)
    private String requestNote;

    // 요청 시점의 기존 시각·주소 - 반영 후에도 변경 전 값 보존 (C17)
    @Column(nullable = false)
    private Instant prevStartAt;

    @Column(nullable = false)
    private Instant prevEndAt;

    private String prevAddress;

    // 사장님이 제안한 시각만 넣음
    private Instant proposedStartAt;

    private Instant proposedEndAt;

    // 주소 변경 요청일 때 (C08·B14·B23)
    private String proposedAddress;

    @Column(precision = 10, scale = 7)
    private BigDecimal proposedLat;

    @Column(precision = 10, scale = 7)
    private BigDecimal proposedLng;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationChangeStatus status = ReservationChangeStatus.REQUESTED;

    private Instant resolvedAt;

    // 사장님 제안 기한 - 제안 후 24시간과 제안 방문 2시간 전 중 빠른 시각, 지나면 EXPIRED
    private Instant expiresAt;

    // CLOSED일 때 이유
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ReservationChangeCloseReason closeReason;

    // 요청을 끝낸 쪽 (SYSTEM = 만료·자동 종료)
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ChangedBy resolvedBy;

    // 요청 당시 reservation.version - 반영할 때 현재 버전과 다르면 덮어쓰지 않고 재조회
    @Column(nullable = false)
    private int baseVersion;

    @Builder
    private ReservationChange(Reservation reservation, ReservationChangeType changeType, RequestedBy requestedBy,
                              String requestNote, Instant prevStartAt, Instant prevEndAt, String prevAddress,
                              Instant proposedStartAt, Instant proposedEndAt, String proposedAddress,
                              BigDecimal proposedLat, BigDecimal proposedLng, Instant expiresAt, int baseVersion) {
        this.reservation = reservation;
        this.changeType = changeType;
        this.requestedBy = requestedBy;
        this.requestNote = requestNote;
        this.prevStartAt = prevStartAt;
        this.prevEndAt = prevEndAt;
        this.prevAddress = prevAddress;
        this.proposedStartAt = proposedStartAt;
        this.proposedEndAt = proposedEndAt;
        this.proposedAddress = proposedAddress;
        this.proposedLat = proposedLat;
        this.proposedLng = proposedLng;
        this.expiresAt = expiresAt;
        this.baseVersion = baseVersion;
    }
}
