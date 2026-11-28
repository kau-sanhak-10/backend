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

// 공식 매뉴얼의 오류코드 한 줄 (AI-14) - '에러코드별 자가 조치' 표에서 옮김
// 조치 단계는 옮기지 않음 - 오류 이름과 그 코드가 무엇인지 적은 문장, 쪽 번호만
@Getter
@Entity
@Immutable
@Table(name = "official_manual_error_code")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OfficialManualErrorCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manual_id", nullable = false)
    private OfficialManual manual;

    // 매뉴얼에 적힌 그대로 (예: E001)
    @Column(nullable = false, length = 20)
    private String code;

    // '이상 발생 내용' 칸 (예: 점화에러)
    @Column(nullable = false, length = 100)
    private String title;

    // 그 코드가 무엇인지 적은 매뉴얼 문장 그대로 - 없으면 null
    @Column(length = 500)
    private String description;

    // 매뉴얼에 인쇄된 쪽 번호
    @Column(nullable = false)
    private int page;
}
