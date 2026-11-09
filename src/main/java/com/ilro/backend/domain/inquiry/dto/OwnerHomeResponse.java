package com.ilro.backend.domain.inquiry.dto;

import com.ilro.backend.domain.customer.entity.Customer;
import com.ilro.backend.domain.owner.entity.WorkCategory;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.global.common.util.KstTime;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

// 사장님 홈 (B-10) - 오늘(한국 날짜) 방문·확인 필요 문의 수·대기 중 상담 요청 수·미확인 알림 수
// 조회에 실패하면 0건으로 보여 주지 않고 오류를 돌려줌
public record OwnerHomeResponse(LocalDate today, long needsCheckCount, long waitingHandoffCount,
                                long unreadNotificationCount, List<TodayVisit> todayVisits) {

    // 취소되지 않은 오늘 예약 - 시작 시각 순
    public record TodayVisit(Long reservationId, Long inquiryId, ReservationStatus status, OffsetDateTime startAt,
                             OffsetDateTime endAt, String customerName, String address, WorkCategory workCategory) {

        public static TodayVisit from(Reservation reservation) {
            Customer customer = reservation.getCustomer();
            return new TodayVisit(reservation.getId(),
                    reservation.getInquiry() == null ? null : reservation.getInquiry().getId(),
                    reservation.getStatus(), KstTime.of(reservation.getStartAt()), KstTime.of(reservation.getEndAt()),
                    customer == null ? null : customer.getName(), reservation.getAddress(),
                    reservation.getWorkType() == null ? null : reservation.getWorkType().getWorkCategory());
        }
    }
}
