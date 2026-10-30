package com.ilro.backend.domain.reservation.entity;

import com.ilro.backend.domain.customer.entity.Customer;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.entity.OwnerWorkType;
import com.ilro.backend.global.entity.BaseTimeEntity;
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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

// 방문 예약
// DB 제약: 같은 업체의 CONFIRMED 예약끼리 시간 겹침 금지, 문의당 CANCELED 아닌 예약 1건
@Getter
@Entity
@Table(name = "reservation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 수동 예약이면 null
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inquiry_id")
    private Inquiry inquiry;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private Owner owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(nullable = false)
    private Instant startAt;

    @Column(nullable = false)
    private Instant endAt;

    private String address;

    @Column(precision = 10, scale = 7)
    private BigDecimal lat;

    @Column(precision = 10, scale = 7)
    private BigDecimal lng;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status = ReservationStatus.REQUESTED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_type_id")
    private OwnerWorkType workType;

    // 계산에 쓴 이동·작업·여유시간(분) - 설정이 바뀌어도 당시 값 보존 (B08·B28)
    private Integer travelTimeMinutes;

    private Integer workMinutes;

    private Integer bufferMinutes;

    // 사장님 직접 등록
    @Column(nullable = false)
    private boolean isManual;

    // 예약 확정 요청 키 - 반복 요청으로 중복 예약 방지 (C13)
    @Column(length = 64)
    private String idempotencyKey;

    // 요청 지문 SHA-256(경로 + 요청 주체 + 정규화한 본문) - 같은 키로 다른 요청이 오면 409, 키와 같이 있거나 같이 없음
    @Column(length = 64)
    private String idempotencyHash;

    // 낙관적 잠금 - 변경 수락과 취소 동시 처리 방지 (B19·B21)
    @Version
    @Column(nullable = false)
    private Integer version;

    @Builder
    private Reservation(Inquiry inquiry, Owner owner, Customer customer, Instant startAt, Instant endAt,
                        String address, BigDecimal lat, BigDecimal lng, OwnerWorkType workType,
                        Integer travelTimeMinutes, Integer workMinutes, Integer bufferMinutes, boolean isManual,
                        String idempotencyKey, String idempotencyHash) {
        this.inquiry = inquiry;
        this.owner = owner;
        this.customer = customer;
        this.startAt = startAt;
        this.endAt = endAt;
        this.address = address;
        this.lat = lat;
        this.lng = lng;
        this.workType = workType;
        this.travelTimeMinutes = travelTimeMinutes;
        this.workMinutes = workMinutes;
        this.bufferMinutes = bufferMinutes;
        this.isManual = isManual;
        this.idempotencyKey = idempotencyKey;
        this.idempotencyHash = idempotencyHash;
    }
}
