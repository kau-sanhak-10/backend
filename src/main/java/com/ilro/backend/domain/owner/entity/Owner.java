package com.ilro.backend.domain.owner.entity;

import com.ilro.backend.domain.admin.entity.Admin;
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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;

// 업체(사장님 계정)
@Getter
@Entity
@Table(name = "owner")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Owner extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 고객에게 보여줄 대표 연락처, 비어 있으면 phone 사용 (B11)
    @Column(length = 20)
    private String contactPhone;

    // 대표자 이름 (B03)
    @Column(length = 50)
    private String ownerName;

    // 상호
    @Column(nullable = false, length = 100)
    private String businessName;

    // 로그인 전화번호
    @Column(nullable = false, length = 20)
    private String phone;

    // 기본 출발지
    private String address;

    @Column(precision = 10, scale = 7)
    private BigDecimal lat;

    @Column(precision = 10, scale = 7)
    private BigDecimal lng;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TravelMode travelMode = TravelMode.CAR;

    // 예약 사이 여유시간(분), 이동시간과 별도 (B08)
    @Column(nullable = false)
    private int bufferMinutes = 15;

    private LocalTime lunchStart;

    private LocalTime lunchEnd;

    private Integer maxDailyVisits;

    // 승인만으로 켜지지 않음, 필수 운영 설정 후 사장님이 직접 켬
    @Column(nullable = false)
    private boolean autoBookingEnabled = false;

    // 업체 고정 접수 링크 토큰
    @Column(nullable = false, length = 64)
    private String linkToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OwnerStatus status = OwnerStatus.READY;

    // 신규 접수 중지 (B09), 기존 대화·예약 접근은 유지
    @Column(nullable = false)
    private boolean intakePaused = false;

    // 고객 안내 문구 (B05)
    @Column(length = 500)
    private String customerNotice;

    // 알림 유형별 수신 여부 (B27)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String notifySettings;

    // 검토 시각·운영자 (승인·거절 공통, 검토 전이면 null)
    private Instant reviewedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private Admin reviewedBy;

    // 고객에게 보여줄 제공 서비스 문구 (B11), 실제 예약 가능 작업은 OwnerWorkType 기준
    private String serviceDescription;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApprovalStatus approvalStatus = ApprovalStatus.PENDING;

    // REJECTED일 때 필수
    private String rejectionReason;

    @Builder
    private Owner(String businessName, String ownerName, String phone, String linkToken) {
        this.businessName = businessName;
        this.ownerName = ownerName;
        this.phone = phone;
        this.linkToken = linkToken;
    }

    // 대표자 이름 (B-03) - 로그인 번호는 운영팀만 바꿈 (O-03)
    public void changeOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    // 업체 정보 (B-05) - 출발지 주소와 좌표는 함께 바뀜
    public void updateBusinessInfo(String businessName, String contactPhone, String address, BigDecimal lat,
                                   BigDecimal lng, String customerNotice, String serviceDescription) {
        this.businessName = businessName;
        this.contactPhone = contactPhone;
        this.address = address;
        this.lat = lat;
        this.lng = lng;
        this.customerNotice = customerNotice;
        this.serviceDescription = serviceDescription;
    }

    // 예약 기준 (B-07·B-08) - 기존 예약은 바꾸지 않음
    public void updateBookingRules(LocalTime lunchStart, LocalTime lunchEnd, Integer maxDailyVisits,
                                   int bufferMinutes, TravelMode travelMode) {
        this.lunchStart = lunchStart;
        this.lunchEnd = lunchEnd;
        this.maxDailyVisits = maxDailyVisits;
        this.bufferMinutes = bufferMinutes;
        this.travelMode = travelMode;
    }

    public void changeAutoBooking(boolean enabled) {
        this.autoBookingEnabled = enabled;
    }

    // 신규 접수 중지·재개 (B-09)
    public void changeIntakePaused(boolean paused) {
        this.intakePaused = paused;
    }

    public boolean hasStartAddress() {
        return address != null && lat != null && lng != null;
    }
}
