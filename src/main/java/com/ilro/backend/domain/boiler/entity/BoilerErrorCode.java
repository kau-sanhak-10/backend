package com.ilro.backend.domain.boiler.entity;

import com.ilro.backend.domain.owner.entity.WorkCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 보일러 오류코드 표 (error_code 테이블) - 공통 에러 코드(global.exception.ErrorCode)와 이름이 겹치지 않게 Boiler를 붙임
@Getter
@Entity
@Table(name = "error_code")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BoilerErrorCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String manufacturer;

    // 예: E01
    @Column(nullable = false, length = 20)
    private String code;

    // 예: 점화 불량
    @Column(nullable = false)
    private String meaning;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private WorkCategory workCategory;

    // 챙겨갈 부품 가능성
    private String partsHint;

    // 무조건 사장님 확인 대상
    @Column(nullable = false)
    private boolean requiresOwnerCheck;

    // 출처 URL
    private String source;

    @Builder
    private BoilerErrorCode(String manufacturer, String code, String meaning, WorkCategory workCategory,
                            String partsHint, boolean requiresOwnerCheck, String source) {
        this.manufacturer = manufacturer;
        this.code = code;
        this.meaning = meaning;
        this.workCategory = workCategory;
        this.partsHint = partsHint;
        this.requiresOwnerCheck = requiresOwnerCheck;
        this.source = source;
    }
}
