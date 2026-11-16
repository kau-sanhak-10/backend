package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.auth.entity.AuthTokenScope;
import com.ilro.backend.domain.auth.entity.AuthTokenType;
import com.ilro.backend.domain.auth.exception.AuthErrorCode;
import com.ilro.backend.domain.reservation.dto.CustomerReservationDetailResponse;
import com.ilro.backend.domain.reservation.dto.CustomerReservationsResponse;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationChange;
import com.ilro.backend.domain.reservation.entity.ReservationChangeStatus;
import com.ilro.backend.domain.reservation.exception.ReservationErrorCode;
import com.ilro.backend.domain.reservation.repository.ReservationChangeRepository;
import com.ilro.backend.domain.reservation.repository.ReservationRepository;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.util.AccessLog;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

// 고객 예약 조회 (C-14·C-17, 명세 1.5·4.3, BE-11)
// 목록: 예약 조회 인증(RESERVATION_LOOKUP)으로 받은 고객 토큰만 - 인증한 번호 + 그 업체의 예약 (예약번호·전화번호만으로는 볼 수 없음)
// 상세: 문의 토큰이면 그 문의에서 확정한 예약만, 고객 토큰이면 그 번호·그 업체의 예약만
//   남의 예약은 있다는 사실도 숨기고 404 RESERVATION_NOT_FOUND
@Service
@RequiredArgsConstructor
public class CustomerReservationService {

    // 한 번에 보여 줄 예약 수 - 방문일이 늦은 것부터
    static final int MAX_RESERVATIONS = 50;

    private static final Set<ReservationChangeStatus> ACTIVE_CHANGE = EnumSet.of(
            ReservationChangeStatus.REQUESTED, ReservationChangeStatus.PROPOSED, ReservationChangeStatus.ACCEPTED);

    private final ReservationRepository reservationRepository;
    private final ReservationChangeRepository changeRepository;

    @Transactional(readOnly = true)
    public CustomerReservationsResponse list(AuthPrincipal principal) {
        requireLookupToken(principal);
        List<Reservation> reservations = reservationRepository.findByCustomerPhone(principal.ownerId(),
                principal.customerPhone(), PageRequest.of(0, MAX_RESERVATIONS));
        reservations.forEach(reservation -> AccessLog.record(principal, "VIEW", "reservation", reservation.getId()));
        return CustomerReservationsResponse.from(reservations);
    }

    @Transactional(readOnly = true)
    public CustomerReservationDetailResponse detail(AuthPrincipal principal, Long reservationId) {
        if (principal.type() == AuthTokenType.CUSTOMER) {
            requireLookupToken(principal);
        } else if (principal.type() != AuthTokenType.INQUIRY) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        Reservation reservation = reservationRepository.findById(reservationId)
                .filter(found -> canSee(principal, found))
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND));
        ReservationChange activeChange = changeRepository
                .findFirstByReservationIdAndStatusIn(reservationId, ACTIVE_CHANGE)
                .orElse(null);
        AccessLog.record(principal, "VIEW", "reservation", reservationId);
        return CustomerReservationDetailResponse.of(reservation, activeChange);
    }

    // 문의 토큰 → 403, 상담 복구로 받은 고객 토큰 → 403 VERIFICATION_PURPOSE_MISMATCH (예약 조회 인증을 다시 받아야 함)
    private static void requireLookupToken(AuthPrincipal principal) {
        if (principal.type() != AuthTokenType.CUSTOMER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        if (principal.scope() != AuthTokenScope.RESERVATION_LOOKUP) {
            throw new BusinessException(AuthErrorCode.VERIFICATION_PURPOSE_MISMATCH);
        }
    }

    private static boolean canSee(AuthPrincipal principal, Reservation reservation) {
        if (principal.type() == AuthTokenType.INQUIRY) {
            return reservation.getInquiry() != null && reservation.getInquiry().getId().equals(principal.inquiryId());
        }
        return reservation.getOwner().getId().equals(principal.ownerId())
                && reservation.getCustomer() != null
                && reservation.getCustomer().getPhone().equals(principal.customerPhone());
    }
}
