package com.ilro.backend.domain.inquiry.entity;

import com.ilro.backend.domain.boiler.entity.BoilerErrorCode;
import com.ilro.backend.domain.customer.entity.Customer;
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
import java.util.Objects;

// 고객 문의(상담) 1건
@Getter
@Entity
@Table(name = "inquiry")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inquiry extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private Owner owner;

    // 초기에는 null 가능
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InquiryStatus status = InquiryStatus.COLLECTING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InquiryChannel channel = InquiryChannel.WEB;

    private String symptom;

    // 방문 주소 - 지도 API엔 이것만 사용 (C04)
    private String address;

    @Column(precision = 10, scale = 7)
    private BigDecimal lat;

    @Column(precision = 10, scale = 7)
    private BigDecimal lng;

    // 주차·출입 등 추가 안내 (C06)
    private String accessNote;

    // LLM 추출 현재값 - 출처·확인 여부는 InquiryField
    @Column(length = 50)
    private String manufacturer;

    @Column(length = 100)
    private String model;

    @Column(length = 50)
    private String boilerType;

    // 오류코드 원문
    @Column(length = 20)
    private String errorCode;

    // 오류코드 표 조회에 성공했을 때
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "error_code_id")
    private BoilerErrorCode boilerErrorCode;

    // 분류된 작업 유형
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_type_id")
    private OwnerWorkType workType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private HandlerMode handlerMode = HandlerMode.AI;

    // 낙관적 잠금
    @Version
    @Column(nullable = false)
    private Integer version;

    // AI 입력 순번 - AI 결과가 오래됐는지 판단 (version과 따로 오름)
    // InquiryRepository.incrementAiInputSeq로만 올림. Entity 저장이 덮어쓰지 않게 insert·update에서 제외
    @Column(nullable = false, insertable = false, updatable = false)
    private long aiInputSeq;

    // true면 자동 예약·AI 재개 차단 (C09·B16)
    @Column(nullable = false)
    private boolean safetyFlag = false;

    // AI 요약 (B13), 생성 실패 시 null
    @Column(columnDefinition = "text")
    private String aiSummary;

    // 값이 있으면 목록·상세 조회 차단 (B12·O09)
    private Instant deletedAt;

    // 상세 주소 (동·호수)
    private String addressDetail;

    // 고객 전화번호 인증 시각, null이면 예약 확정 불가
    private Instant phoneVerifiedAt;

    @Builder
    private Inquiry(Owner owner, Customer customer) {
        this.owner = owner;
        this.customer = customer;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    // AI가 답하는 상담 - AI 응대 중이고 정보 수집·시간 선택 단계일 때만
    // 사장님 확인·예약·서비스 불가·종료 단계는 사장님이 이어감
    public boolean isAiAnswering() {
        return handlerMode == HandlerMode.AI
                && (status == InquiryStatus.COLLECTING || status == InquiryStatus.SCHEDULING);
    }

    // AI 판단·사장님 확인 전환에 따른 진행 단계 (명세 2.1·6.4)
    public void changeStatus(InquiryStatus status) {
        this.status = status;
    }

    // 안전 신호 - 자동 예약·AI 재개 차단 (C09·B16)
    public void flagSafety() {
        this.safetyFlag = true;
    }

    // 고객 방문정보 (C-06) - 연락처를 다른 번호로 바꾸면 그 번호로 다시 인증해야 예약 확정 가능 (명세 1.5)
    public void updateVisitInfo(Customer customer, String address, String addressDetail, BigDecimal lat,
                                BigDecimal lng, String accessNote) {
        if (this.customer == null || !this.customer.getPhone().equals(customer.getPhone())) {
            this.phoneVerifiedAt = null;
        }
        this.customer = customer;
        this.address = address;
        this.addressDetail = addressDetail;
        this.lat = lat;
        this.lng = lng;
        this.accessNote = accessNote;
    }

    public void changeWorkType(OwnerWorkType workType) {
        this.workType = workType;
    }

    // 접수 항목 현재값을 문의 칸에도 반영 (현재값 조회·AI 입력용, 출처·이력은 InquiryField)
    // 오류코드가 바뀌면 이전 오류코드 표 연결은 맞지 않으므로 끊음
    public void applyField(InquiryFieldKey key, String value) {
        switch (key) {
            case MANUFACTURER -> this.manufacturer = value;
            case MODEL -> this.model = value;
            case BOILER_TYPE -> this.boilerType = value;
            case SYMPTOM -> this.symptom = value;
            case ERROR_CODE -> {
                if (!Objects.equals(this.errorCode, value)) {
                    this.boilerErrorCode = null;
                }
                this.errorCode = value;
            }
        }
    }
}
