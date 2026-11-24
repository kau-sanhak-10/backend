package com.ilro.backend.domain.reservation.dto;

import com.ilro.backend.domain.customer.entity.Customer;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.owner.entity.WorkCategory;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationChange;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.global.common.util.KstTime;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;

// 방문 작업정보 (B-23, 명세 4.6, BE-13) - 사장님 API에서만
// inquiryId: 예약을 잡은 문의 (수동 예약·삭제된 문의면 null) - 증상·사진·AI 요약은 문의 상세(B-13)에서
// addressDetail·accessNote: 문의에 적은 값 - 예약 주소가 문의 주소와 다르면(변경) 다른 집 정보일 수 있어 null
// completedAt: 완료된 예약만, version: 완료·변경·취소 요청 때 보냄 (명세 1.7), activeChange: 진행 중 변경 (없으면 null)
// 작업 메모는 GET …/work-note (B-24)
public record OwnerReservationDetailResponse(Long reservationId, Long inquiryId, ReservationStatus status, int version,
                                             OffsetDateTime startAt, OffsetDateTime endAt, Integer workMinutes,
                                             WorkCategory workCategory, boolean isManual, String customerName,
                                             String customerPhone, String address, String addressDetail,
                                             BigDecimal lat, BigDecimal lng, String accessNote,
                                             OffsetDateTime completedAt, ChangeView activeChange) {

    public static OwnerReservationDetailResponse of(Reservation reservation, Instant completedAt,
                                                    ReservationChange activeChange) {
        Customer customer = reservation.getCustomer();
        // 삭제된 문의는 조회를 막으므로 없는 것처럼
        Inquiry inquiry = reservation.getInquiry() == null || reservation.getInquiry().getDeletedAt() != null
                ? null : reservation.getInquiry();
        boolean sameAddress = inquiry != null && Objects.equals(inquiry.getAddress(), reservation.getAddress());
        return new OwnerReservationDetailResponse(reservation.getId(), inquiry == null ? null : inquiry.getId(),
                reservation.getStatus(), reservation.getVersion(), KstTime.of(reservation.getStartAt()),
                KstTime.of(reservation.getEndAt()), reservation.getWorkMinutes(),
                reservation.getWorkType() == null ? null : reservation.getWorkType().getWorkCategory(),
                reservation.isManual(), customer == null ? null : customer.getName(),
                customer == null ? null : customer.getPhone(), reservation.getAddress(),
                sameAddress ? inquiry.getAddressDetail() : null, reservation.getLat(), reservation.getLng(),
                sameAddress ? inquiry.getAccessNote() : null, KstTime.of(completedAt),
                ChangeView.from(activeChange));
    }
}
