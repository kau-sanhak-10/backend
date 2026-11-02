package com.ilro.backend.domain.owner.entity;

import jakarta.persistence.Column;
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

// 서비스 지역 (시군구 단위)
@Getter
@Entity
@Table(name = "owner_service_area")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OwnerServiceArea {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private Owner owner;

    // 시군구 코드 (카카오 주소 API), 고객 주소 코드와 비교
    @Column(nullable = false, length = 10)
    private String regionCode;

    // 화면 표시용 (예: 고양시 덕양구)
    @Column(nullable = false, length = 50)
    private String regionName;

    @Builder
    private OwnerServiceArea(Owner owner, String regionCode, String regionName) {
        this.owner = owner;
        this.regionCode = regionCode;
        this.regionName = regionName;
    }
}
