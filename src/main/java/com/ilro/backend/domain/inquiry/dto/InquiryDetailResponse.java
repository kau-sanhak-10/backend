package com.ilro.backend.domain.inquiry.dto;

import com.ilro.backend.domain.ai.entity.AiAction;
import com.ilro.backend.domain.customer.entity.Customer;
import com.ilro.backend.domain.inquiry.entity.ConsentType;
import com.ilro.backend.domain.inquiry.entity.HandlerMode;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.entity.InquiryStatus;
import com.ilro.backend.domain.owner.entity.WorkCategory;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.global.common.util.KstTime;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

// 고객이 보는 문의 (C-03·C-09) - 상태·응대 주체·다음 안내·방문정보·연결 예약
// nextAction: AI가 적용한 마지막 판단의 다음 행동, 사장님 응대 중이거나 아직 판단 전이면 null
// version: 방문정보·접수정보를 고칠 때 그대로 보냄 (명세 1.7)
public record InquiryDetailResponse(Long inquiryId, InquiryStatus status, HandlerMode handlerMode, AiAction nextAction,
                                    int version, VisitInfo visitInfo, boolean phoneVerified,
                                    List<ConsentType> consents, LinkedReservation reservation,
                                    OffsetDateTime createdAt) {

    // 아직 입력 전인 칸은 null
    // name은 번호 인증 전이면서 다른 문의·예약도 쓰는 고객이면 null (같은 번호를 넣은 남의 이름을 보여 주지 않음)
    public record VisitInfo(String name, String phone, String address, String addressDetail, BigDecimal lat,
                            BigDecimal lng, String accessNote, WorkCategory workCategory) {
    }

    // 취소되지 않은 예약 1건, 없으면 null
    public record LinkedReservation(Long reservationId, ReservationStatus status, OffsetDateTime startAt,
                                    OffsetDateTime endAt) {
    }

    public static InquiryDetailResponse of(Inquiry inquiry, boolean showName, AiAction nextAction,
                                           List<ConsentType> consents, Reservation reservation) {
        Customer customer = inquiry.getCustomer();
        VisitInfo visitInfo = new VisitInfo(
                customer == null || !showName ? null : customer.getName(),
                customer == null ? null : customer.getPhone(),
                inquiry.getAddress(), inquiry.getAddressDetail(), inquiry.getLat(), inquiry.getLng(),
                inquiry.getAccessNote(),
                inquiry.getWorkType() == null ? null : inquiry.getWorkType().getWorkCategory());
        LinkedReservation linked = reservation == null ? null : new LinkedReservation(reservation.getId(),
                reservation.getStatus(), KstTime.of(reservation.getStartAt()), KstTime.of(reservation.getEndAt()));
        return new InquiryDetailResponse(inquiry.getId(), inquiry.getStatus(), inquiry.getHandlerMode(), nextAction,
                inquiry.getVersion(), visitInfo, inquiry.getPhoneVerifiedAt() != null, consents, linked,
                KstTime.of(inquiry.getCreatedAt()));
    }
}
