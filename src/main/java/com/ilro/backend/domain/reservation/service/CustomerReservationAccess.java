package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.auth.entity.AuthTokenScope;
import com.ilro.backend.domain.auth.entity.AuthTokenType;
import com.ilro.backend.domain.auth.exception.AuthErrorCode;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.exception.ReservationErrorCode;
import com.ilro.backend.domain.reservation.repository.ReservationRepository;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 고객이 다룰 수 있는 예약 (명세 1.5) - 상세·변경 요청·수락·거절·취소가 같은 규칙을 씀
// 문의 토큰: 그 문의에서 확정한 예약만 / 고객 토큰: 예약 조회 인증(RESERVATION_LOOKUP)으로 받은 것만, 그 번호·그 업체 예약만
// 남의 예약은 있다는 사실도 숨기고 404 RESERVATION_NOT_FOUND
@Component
@RequiredArgsConstructor
public class CustomerReservationAccess {

    private final ReservationRepository reservationRepository;

    public Reservation find(AuthPrincipal principal, Long reservationId) {
        checkToken(principal);
        return reservationRepository.findById(reservationId)
                .filter(reservation -> canSee(principal, reservation))
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND));
    }

    // 문의 토큰 또는 예약 조회 고객 토큰
    public static void checkToken(AuthPrincipal principal) {
        if (principal.type() == AuthTokenType.CUSTOMER) {
            requireLookupToken(principal);
        } else if (principal.type() != AuthTokenType.INQUIRY) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }

    // 문의 토큰 → 403, 상담 복구로 받은 고객 토큰 → 403 VERIFICATION_PURPOSE_MISMATCH (예약 조회 인증을 다시 받아야 함)
    public static void requireLookupToken(AuthPrincipal principal) {
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
