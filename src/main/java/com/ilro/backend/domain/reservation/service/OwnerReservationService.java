package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.reservation.dto.OwnerReservationDetailResponse;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationChange;
import com.ilro.backend.domain.reservation.entity.ReservationHistory;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.domain.reservation.exception.ReservationErrorCode;
import com.ilro.backend.domain.reservation.repository.ReservationChangeRepository;
import com.ilro.backend.domain.reservation.repository.ReservationHistoryRepository;
import com.ilro.backend.domain.reservation.repository.ReservationRepository;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.util.AccessLog;
import com.ilro.backend.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

// 사장님 방문 작업정보 (B-23, 명세 1.8·4.6, BE-13) - 자기 업체 예약만, 다른 업체 예약은 404
@Service
@RequiredArgsConstructor
public class OwnerReservationService {

    private final ReservationRepository reservationRepository;
    private final ReservationChangeRepository changeRepository;
    private final ReservationHistoryRepository historyRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public OwnerReservationDetailResponse detail(AuthPrincipal principal, Long reservationId) {
        Reservation reservation = reservationRepository.findByIdAndOwnerId(reservationId, principal.ownerId())
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND));
        // 기한이 지난 제안은 상태가 아직 PROPOSED여도 진행 중 변경으로 보여 주지 않음
        Instant now = clock.instant();
        ReservationChange activeChange = changeRepository
                .findFirstByReservationIdAndStatusIn(reservationId, CustomerReservationService.ACTIVE_CHANGE)
                .filter(change -> !change.isExpiredProposal(now))
                .orElse(null);
        // 응답을 다 만든 뒤에 조회 기록 - 만들다 실패한 요청이 성공으로 남지 않게
        OwnerReservationDetailResponse response = OwnerReservationDetailResponse.of(reservation,
                completedAt(reservation), activeChange);
        AccessLog.record(principal, "VIEW", "reservation", reservationId);
        return response;
    }

    // 정정 뒤 다시 완료할 수 있어 마지막 완료 이력의 시각
    private Instant completedAt(Reservation reservation) {
        if (reservation.getStatus() != ReservationStatus.COMPLETED) {
            return null;
        }
        return historyRepository
                .findFirstByReservationIdAndToStatusOrderByIdDesc(reservation.getId(), ReservationStatus.COMPLETED)
                .map(ReservationHistory::getCreatedAt)
                .orElse(null);
    }
}
