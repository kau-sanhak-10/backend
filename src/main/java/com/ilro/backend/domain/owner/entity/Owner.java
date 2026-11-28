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

    // 운영팀 등록(B-01)은 대표 연락처·주소·서비스 문구까지 받음 - 좌표는 사장님이 업체 정보(B-05)에서 채움
    @Builder
    private Owner(String businessName, String ownerName, String phone, String linkToken, String contactPhone,
                  String address, String serviceDescription) {
        this.businessName = businessName;
        this.ownerName = ownerName;
        this.phone = phone;
        this.linkToken = linkToken;
        this.contactPhone = contactPhone;
        this.address = address;
        this.serviceDescription = serviceDescription;
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

    // 사장님 탈퇴 (B-04, BE-16) - 고객 예약·문의 기록은 그대로, 로그인·접수 링크·자동 예약은 막힘
    public void withdraw() {
        this.status = OwnerStatus.WITHDRAWN;
        this.autoBookingEnabled = false;
    }

    public void changeAutoBooking(boolean enabled) {
        this.autoBookingEnabled = enabled;
    }

    // 신규 접수 중지·재개 (B-09)
    public void changeIntakePaused(boolean paused) {
        this.intakePaused = paused;
    }

    // 알림 유형별 푸시 수신 여부 (B-27, BE-14) - 4개 유형을 JSON으로 통째로 바꿈
    public void changeNotifySettings(String notifySettings) {
        this.notifySettings = notifySettings;
    }

    public boolean hasStartAddress() {
        return address != null && lat != null && lng != null;
    }

    // 접수 링크가 살아 있는지 - 승인 전·거절·탈퇴 업체의 링크는 없는 것으로 봄
    public boolean isIntakeLinkValid() {
        return approvalStatus == ApprovalStatus.APPROVED && status != OwnerStatus.WITHDRAWN;
    }

    // 고객 상담을 받을 수 있는 상태 - 설정 중(READY)·이용 제한이면 새 상담도 진행 중 대화도 막음
    public boolean isServingCustomers() {
        return approvalStatus == ApprovalStatus.APPROVED && status == OwnerStatus.ACTIVE;
    }

    // 접수 링크 발급·재발급 (O-04) - 토큰을 바꾸면 기존 링크는 폐기됨
    public void changeLinkToken(String linkToken) {
        this.linkToken = linkToken;
    }

    // ---------- 운영팀 (명세 5.8, BE-17) - 아래 네 가지는 서로 독립: 하나가 바뀌어도 다른 것을 켜지 않음 ----------

    // 신청 심사 - 승인. 운영 상태(READY)·자동 예약(꺼짐)은 그대로
    public void approve(Admin reviewer, Instant now) {
        this.approvalStatus = ApprovalStatus.APPROVED;
        this.rejectionReason = null;
        this.reviewedBy = reviewer;
        this.reviewedAt = now;
    }

    // 신청 심사 - 거절 (사유 필수, ck_owner_rejection_reason)
    public void reject(Admin reviewer, String reason, Instant now) {
        this.approvalStatus = ApprovalStatus.REJECTED;
        this.rejectionReason = reason;
        this.reviewedBy = reviewer;
        this.reviewedAt = now;
    }

    // 운영 상태 READY·ACTIVE·RESTRICTED - 예약·문의·고객은 건드리지 않음
    public void changeStatus(OwnerStatus status) {
        this.status = status;
    }

    // 로그인 번호 변경 (O-03) - 번호만 바꾸고 업체·예약·고객은 그대로
    public void changeLoginPhone(String phone) {
        this.phone = phone;
    }
}
