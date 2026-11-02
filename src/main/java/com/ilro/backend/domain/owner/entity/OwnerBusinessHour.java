package com.ilro.backend.domain.owner.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
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

import java.time.DayOfWeek;
import java.time.LocalTime;

// 요일별 영업시간 - 업체당 요일 하나에 1행
@Getter
@Entity
@Table(name = "owner_business_hour")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OwnerBusinessHour {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private Owner owner;

    @Convert(converter = DayOfWeekConverter.class)
    @Column(nullable = false)
    private DayOfWeek dayOfWeek;

    private LocalTime openTime;

    private LocalTime closeTime;

    // true면 openTime·closeTime은 비움
    @Column(nullable = false)
    private boolean isClosed;

    @Builder
    private OwnerBusinessHour(Owner owner, DayOfWeek dayOfWeek, LocalTime openTime, LocalTime closeTime, boolean isClosed) {
        this.owner = owner;
        this.dayOfWeek = dayOfWeek;
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.isClosed = isClosed;
    }
}
