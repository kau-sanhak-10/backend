package com.ilro.backend.domain.boiler.entity;

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
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

// 공식 매뉴얼이 다루는 모델 (AI-14) - 한 제조사 안에서 한 모델은 매뉴얼 하나 (DB에서 같은 제조사 복합 FK)
@Getter
@Entity
@Immutable
@Table(name = "official_manual_model")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OfficialManualModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manual_id", nullable = false)
    private OfficialManual manual;

    @Column(nullable = false, length = 50)
    private String manufacturer;

    // 매뉴얼 규격표의 모델명을 '제품 이름-모델명' 꼴로 (예: 거꾸로 ECO 콘덴싱 L20-15HFN)
    @Column(nullable = false, length = 100)
    private String modelName;

    // 비교용 값 - OfficialErrorCodeLookup.modelKey 규칙 (예: 거꾸로ECO콘덴싱L2015HFN)
    @Column(nullable = false, length = 100)
    private String modelKey;
}
