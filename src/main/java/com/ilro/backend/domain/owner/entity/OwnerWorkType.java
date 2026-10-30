package com.ilro.backend.domain.owner.entity;

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

// 업체가 제공하는 작업 종류와 소요시간
@Getter
@Entity
@Table(name = "owner_work_type")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OwnerWorkType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private Owner owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkCategory workCategory;

    // 소요시간(분), AI가 예측하지 않고 여기서 조회
    @Column(nullable = false)
    private int durationMinutes;

    // 삭제 대신 false (과거 기록 보존, B05)
    @Column(nullable = false)
    private boolean isActive = true;

    // 이 작업을 자동 예약해도 되는지 - 제공 여부(isActive)와 별개, 제공하는 작업만 켤 수 있음
    // 업체 자동 예약(owner.autoBookingEnabled)과 둘 다 켜져야 자동 예약
    @Column(nullable = false)
    private boolean autoBookingAllowed = false;

    @Builder
    private OwnerWorkType(Owner owner, WorkCategory workCategory, int durationMinutes) {
        this.owner = owner;
        this.workCategory = workCategory;
        this.durationMinutes = durationMinutes;
    }

    // 제공을 끄면 자동 예약 허용도 같이 꺼짐 (ck_owner_work_type_auto_booking)
    public void update(boolean isActive, int durationMinutes, boolean autoBookingAllowed) {
        this.isActive = isActive;
        this.durationMinutes = durationMinutes;
        this.autoBookingAllowed = isActive && autoBookingAllowed;
    }
}
