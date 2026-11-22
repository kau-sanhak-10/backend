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

    // 고객이 변경 요청에 적은 주소 - 사장님이 주소를 바꾸지 않는 제안을 해도 그대로 남음 (제안을 고쳐 반영할 수 있게)
    private String requestedAddress;

    @Column(precision = 10, scale = 7)
    private BigDecimal requestedLat;

    @Column(precision = 10, scale = 7)
    private BigDecimal requestedLng;

    // 그 제안이 반영할 주소 - 주소를 바꾸지 않는 제안이면 null (C08·B14·B23)
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

    // 요청(제안을 바꿨으면 그 제안) 당시 reservation.version - 반영할 때 현재 버전과 다르면 덮어쓰지 않고 재조회
    @Column(nullable = false)
    private int baseVersion;

    // 진행 중 (예약당 1건, uq_reservation_change_active)
    public boolean isActive() {
        return status == ReservationChangeStatus.REQUESTED || status == ReservationChangeStatus.PROPOSED
                || status == ReservationChangeStatus.ACCEPTED;
    }

    // 사장님 시간 제안 (명세 5.3 2단계) - proposedAddress는 이 제안이 반영할 주소 (주소를 바꾸지 않으면 null)
    // 고객이 요청한 주소(requested*)는 건드리지 않음
    public void propose(Instant proposedStartAt, Instant proposedEndAt, String proposedAddress, BigDecimal proposedLat,
                        BigDecimal proposedLng, Instant expiresAt) {
        this.status = ReservationChangeStatus.PROPOSED;
        this.proposedStartAt = proposedStartAt;
        this.proposedEndAt = proposedEndAt;
        this.proposedAddress = proposedAddress;
        this.proposedLat = proposedLat;
        this.proposedLng = proposedLng;
        this.expiresAt = expiresAt;
    }

    // 답을 기다리는 제안을 다른 내용으로 바꿈 - 예약 version을 올린 뒤 그 값으로 맞춤
    // 앞 제안을 보고 있던 화면의 수락은 버전 충돌이 되어, 바뀐 제안을 다시 보고 수락하게 됨
    public void rebase(int reservationVersion) {
        this.baseVersion = reservationVersion;
    }

    // 지금 답을 기다리는 제안이 이 내용과 같은지 - 같은 제안을 다시 보낸 것(재시도)을 가려냄
    public boolean proposes(Instant startAt, boolean addressApplied) {
        return status == ReservationChangeStatus.PROPOSED && startAt.equals(proposedStartAt)
                && addressApplied == (proposedAddress != null);
    }

    // 고객 수락 뒤 최종 검사 결과 - 같은 트랜잭션에서 ACCEPTED를 거쳐 APPLIED 또는 FAILED
    public void apply(Instant now) {
        resolve(ReservationChangeStatus.APPLIED, ChangedBy.CUSTOMER, now);
    }

    public void fail(Instant now) {
        resolve(ReservationChangeStatus.FAILED, ChangedBy.SYSTEM, now);
    }

    public void reject(Instant now) {
        resolve(ReservationChangeStatus.REJECTED, ChangedBy.CUSTOMER, now);
    }

    public void expire(Instant now) {
        resolve(ReservationChangeStatus.EXPIRED, ChangedBy.SYSTEM, now);
    }

    // 예약 취소·사장님 요청 닫기
    public void close(ReservationChangeCloseReason reason, ChangedBy by, Instant now) {
        resolve(ReservationChangeStatus.CLOSED, by, now);
        this.closeReason = reason;
    }

    private void resolve(ReservationChangeStatus status, ChangedBy by, Instant now) {
        this.status = status;
        this.resolvedBy = by;
        this.resolvedAt = now;
    }

    @Builder
    private ReservationChange(Reservation reservation, ReservationChangeType changeType, RequestedBy requestedBy,
                              String requestNote, Instant prevStartAt, Instant prevEndAt, String prevAddress,
                              String requestedAddress, BigDecimal requestedLat, BigDecimal requestedLng,
                              Instant proposedStartAt, Instant proposedEndAt, String proposedAddress,
                              BigDecimal proposedLat, BigDecimal proposedLng, Instant expiresAt, int baseVersion) {
        this.reservation = reservation;
        this.changeType = changeType;
        this.requestedBy = requestedBy;
        this.requestNote = requestNote;
        this.prevStartAt = prevStartAt;
        this.prevEndAt = prevEndAt;
        this.prevAddress = prevAddress;
        this.requestedAddress = requestedAddress;
        this.requestedLat = requestedLat;
        this.requestedLng = requestedLng;
        this.proposedStartAt = proposedStartAt;
        this.proposedEndAt = proposedEndAt;
        this.proposedAddress = proposedAddress;
        this.proposedLat = proposedLat;
        this.proposedLng = proposedLng;
        this.expiresAt = expiresAt;
        this.baseVersion = baseVersion;
    }

    // 답할 기한이 지난 제안 - 저장된 상태가 아직 PROPOSED여도 고객 화면에 진행 중으로 보여 주지 않음
    // (상태를 EXPIRED로 바꾸는 것은 수락할 때와 만료 작업)
    public boolean isExpiredProposal(Instant now) {
        return status == ReservationChangeStatus.PROPOSED && (expiresAt == null || !expiresAt.isAfter(now));
    }
}
