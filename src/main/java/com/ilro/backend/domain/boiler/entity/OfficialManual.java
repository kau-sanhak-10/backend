package com.ilro.backend.domain.boiler.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;

// 제조사 공식 매뉴얼 한 권 (AI-14) - 마이그레이션으로 넣은 자료를 읽기만 함
@Getter
@Entity
@Immutable
@Table(name = "official_manual")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OfficialManual {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 접수 항목 manufacturer 값과 같은 이름, 띄어쓰기 없이 (예: 귀뚜라미)
    @Column(nullable = false, length = 50)
    private String manufacturer;

    // 자료실에 올라온 제품 이름 (예: 거꾸로 ECO 콘덴싱 L20 가스보일러)
    @Column(nullable = false, length = 100)
    private String productName;

    // 표지의 문서 이름
    @Column(nullable = false, length = 100)
    private String documentName;

    // 뒤표지 아래 문서 코드 (예: S 260911)
    @Column(nullable = false, length = 50)
    private String documentVersion;

    // 제조사 공식 자료실 - 제품 이름으로 찾음
    @Column(nullable = false, length = 500)
    private String sourceUrl;

    // 자료실의 내려받기 주소 (자료실을 거치지 않으면 제조사 사이트가 막을 수 있음)
    @Column(nullable = false, length = 500)
    private String fileUrl;

    // 내려받아 옮겨 적은 날
    @Column(nullable = false)
    private LocalDate checkedOn;
}
