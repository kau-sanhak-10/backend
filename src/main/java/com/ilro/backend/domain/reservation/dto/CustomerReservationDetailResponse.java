package com.ilro.backend.domain.reservation.dto;

import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.entity.WorkCategory;
import com.ilro.backend.domain.reservation.entity.RequestedBy;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationChange;
import com.ilro.backend.domain.reservation.entity.ReservationChangeStatus;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.global.common.util.KstTime;

import java.time.OffsetDateTime;

// 고객 예약 상세 (C-14·C-17, 명세 4.3) - 예약과 진행 중 변경(없으면 null)
// inquiryId: 예약을 잡은 문의 (사장님이 직접 등록한 예약이면 null)
// contactPhone: 업체 대표 연락처, 없으면 사장님 번호 - 완료된 예약의 취소처럼 화면에서 처리할 수 없는 일은 업체에 문의
// version: 변경·취소 요청 때 보내는 기준 버전 (명세 1.7)
public record CustomerReservationDetailResponse(Long reservationId, Long inquiryId, ReservationStatus status,
                                                OffsetDateTime startAt, OffsetDateTime endAt, String address,
                                                WorkCategory workCategory, Integer workMinutes,
                                                String businessName, String contactPhone, int version,
                                                ActiveChange activeChange) {

    // 진행 중 변경 (REQUESTED·PROPOSED·ACCEPTED) - 제안 시각·기한은 사장님이 제안한 뒤에만 있음 (BE-16)
    public record ActiveChange(Long changeId, ReservationChangeStatus status, RequestedBy requestedBy,
                               OffsetDateTime proposedStartAt, OffsetDateTime proposedEndAt,
                               OffsetDateTime expiresAt) {

        static ActiveChange from(ReservationChange change) {
            return change == null ? null : new ActiveChange(change.getId(), change.getStatus(),
                    change.getRequestedBy(), KstTime.of(change.getProposedStartAt()),
                    KstTime.of(change.getProposedEndAt()), KstTime.of(change.getExpiresAt()));
        }
    }

    public static CustomerReservationDetailResponse of(Reservation reservation, ReservationChange activeChange) {
        Owner owner = reservation.getOwner();
        String contactPhone = owner.getContactPhone() != null ? owner.getContactPhone() : owner.getPhone();
        return new CustomerReservationDetailResponse(reservation.getId(),
                reservation.getInquiry() == null ? null : reservation.getInquiry().getId(),
                reservation.getStatus(), KstTime.of(reservation.getStartAt()), KstTime.of(reservation.getEndAt()),
                reservation.getAddress(),
                reservation.getWorkType() == null ? null : reservation.getWorkType().getWorkCategory(),
                reservation.getWorkMinutes(), owner.getBusinessName(), contactPhone, reservation.getVersion(),
                ActiveChange.from(activeChange));
    }
}
