package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.reservation.dto.CustomerReservationDetailResponse;
import com.ilro.backend.domain.reservation.dto.CustomerReservationsResponse;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationChange;
import com.ilro.backend.domain.reservation.entity.ReservationChangeStatus;
import com.ilro.backend.domain.reservation.repository.ReservationChangeRepository;
import com.ilro.backend.domain.reservation.repository.ReservationRepository;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.util.AccessLog;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

// 고객 예약 조회 (C-14·C-17, 명세 1.5·4.3, BE-11)
// 목록: 예약 조회 인증(RESERVATION_LOOKUP)으로 받은 고객 토큰만 - 인증한 번호 + 그 업체의 예약 (예약번호·전화번호만으로는 볼 수 없음)
// 상세: 문의 토큰이면 그 문의에서 확정한 예약만, 고객 토큰이면 그 번호·그 업체의 예약만 (CustomerReservationAccess)
@Service
@RequiredArgsConstructor
public class CustomerReservationService {

    // 한 번에 보여 줄 예약 수 - 방문일이 늦은 것부터
    static final int MAX_RESERVATIONS = 50;

    static final Set<ReservationChangeStatus> ACTIVE_CHANGE = EnumSet.of(
            ReservationChangeStatus.REQUESTED, ReservationChangeStatus.PROPOSED, ReservationChangeStatus.ACCEPTED);

    private final ReservationRepository reservationRepository;
    private final ReservationChangeRepository changeRepository;
    private final CustomerReservationAccess access;
    private final Clock clock;

    @Transactional(readOnly = true)
    public CustomerReservationsResponse list(AuthPrincipal principal) {
        CustomerReservationAccess.requireLookupToken(principal);
        List<Reservation> reservations = reservationRepository.findByCustomerPhone(principal.ownerId(),
                principal.customerPhone(), PageRequest.of(0, MAX_RESERVATIONS));
        CustomerReservationsResponse response = CustomerReservationsResponse.from(reservations);
        reservations.forEach(reservation -> AccessLog.record(principal, "VIEW", "reservation", reservation.getId()));
        return response;
    }

    @Transactional(readOnly = true)
    public CustomerReservationDetailResponse detail(AuthPrincipal principal, Long reservationId) {
        Reservation reservation = access.find(principal, reservationId);
        // 기한이 지난 제안은 상태가 아직 PROPOSED여도 진행 중 변경으로 보여 주지 않음
        Instant now = clock.instant();
        ReservationChange activeChange = changeRepository
                .findFirstByReservationIdAndStatusIn(reservationId, ACTIVE_CHANGE)
                .filter(change -> !change.isExpiredProposal(now))
                .orElse(null);
        // 응답을 다 만든 뒤에 조회 기록 - 만들다 실패한 요청이 성공으로 남지 않게
        CustomerReservationDetailResponse response = CustomerReservationDetailResponse.of(reservation, activeChange);
        AccessLog.record(principal, "VIEW", "reservation", reservationId);
        return response;
    }
}
