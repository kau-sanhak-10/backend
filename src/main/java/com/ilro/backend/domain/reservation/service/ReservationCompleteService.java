package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.reservation.dto.CompleteCorrectionRequest;
import com.ilro.backend.domain.reservation.dto.CompleteCorrectionResponse;
import com.ilro.backend.domain.reservation.dto.ReservationCompleteRequest;
import com.ilro.backend.domain.reservation.dto.ReservationCompleteResponse;
import com.ilro.backend.domain.reservation.entity.ChangedBy;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationChange;
import com.ilro.backend.domain.reservation.entity.ReservationChangeCloseReason;
import com.ilro.backend.domain.reservation.entity.ReservationHistory;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.domain.reservation.exception.ReservationErrorCode;
import com.ilro.backend.domain.reservation.repository.ReservationChangeRepository;
import com.ilro.backend.domain.reservation.repository.ReservationHistoryRepository;
import com.ilro.backend.domain.reservation.repository.ReservationRepository;
import com.ilro.backend.global.common.util.KstTime;
import com.ilro.backend.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

// 작업 완료·완료 정정 (B-25, 명세 4.6, BE-13) - 업체를 잠근 한 트랜잭션에서 예약 상태·version +1·이력
// 완료: 확정 예약만, 방문 시작 시각부터. 진행 중 변경은 CLOSED(OWNER_CLOSED)
//   이미 완료됨 200 alreadyCompleted / 취소 409 RESERVATION_CANCELED / 시작 전 409 RESERVATION_NOT_STARTED
// 정정: 완료 예약만 확정으로, 사유 필수. 완료할 때 닫은 변경 요청은 다시 열지 않음
// 버전 다름 409 + 최신 예약. 고객 알림은 만들지 않음
@Service
@RequiredArgsConstructor
public class ReservationCompleteService {

    private final ScheduleWriter writer;
    private final ReservationRepository reservationRepository;
    private final ReservationChangeRepository changeRepository;
    private final ReservationHistoryRepository historyRepository;
    private final Clock clock;

    public ReservationCompleteResponse complete(Long ownerId, Long reservationId, ReservationCompleteRequest request) {
        return writer.locked(ownerId, owner -> {
            Reservation reservation = find(ownerId, reservationId);
            checkNotCanceled(reservation);
            if (reservation.getStatus() == ReservationStatus.COMPLETED) {
                return new ReservationCompleteResponse(reservation.getId(), reservation.getStatus(),
                        reservation.getVersion(), true, List.of());
            }
            ReservationCancelService.checkVersion(reservation, request.version());
            if (!reservation.isConfirmed()) {
                throw new BusinessException(ReservationErrorCode.RESERVATION_NOT_CHANGEABLE,
                        Map.of("status", reservation.getStatus()));
            }
            Instant now = clock.instant();
            if (now.isBefore(reservation.getStartAt())) {
                throw new BusinessException(ReservationErrorCode.RESERVATION_NOT_STARTED,
                        Map.of("startAt", KstTime.of(reservation.getStartAt())));
            }

            reservation.complete();
            saveHistory(reservation, ReservationStatus.CONFIRMED, null);
            List<ReservationChange> active = changeRepository.findByReservationIdAndStatusIn(reservation.getId(),
                    CustomerReservationService.ACTIVE_CHANGE);
            active.forEach(change -> change.close(ReservationChangeCloseReason.OWNER_CLOSED, ChangedBy.OWNER, now));
            reservationRepository.flush();
            return new ReservationCompleteResponse(reservation.getId(), reservation.getStatus(),
                    reservation.getVersion(), false, active.stream().map(ReservationChange::getId).toList());
        });
    }

    public CompleteCorrectionResponse correct(Long ownerId, Long reservationId, CompleteCorrectionRequest request) {
        return writer.locked(ownerId, owner -> {
            Reservation reservation = find(ownerId, reservationId);
            checkNotCanceled(reservation);
            if (reservation.getStatus() != ReservationStatus.COMPLETED) {
                throw new BusinessException(ReservationErrorCode.RESERVATION_NOT_COMPLETED,
                        Map.of("status", reservation.getStatus()));
            }
            ReservationCancelService.checkVersion(reservation, request.version());

            reservation.correctCompletion();
            saveHistory(reservation, ReservationStatus.COMPLETED, request.reason().trim());
            // 다른 확정 예약과 겹치면 여기서 ex_reservation_confirmed_overlap → 409 SLOT_UNAVAILABLE
            reservationRepository.flush();
            return new CompleteCorrectionResponse(reservation.getId(), reservation.getStatus(),
                    reservation.getVersion());
        });
    }

    // 다른 업체 예약은 없는 것처럼 404
    private Reservation find(Long ownerId, Long reservationId) {
        return reservationRepository.findByIdAndOwnerId(reservationId, ownerId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND));
    }

    private static void checkNotCanceled(Reservation reservation) {
        if (reservation.getStatus() == ReservationStatus.CANCELED) {
            throw new BusinessException(ReservationErrorCode.RESERVATION_CANCELED);
        }
    }

    private void saveHistory(Reservation reservation, ReservationStatus from, String reason) {
        historyRepository.save(ReservationHistory.builder()
                .reservation(reservation)
                .fromStatus(from)
                .toStatus(reservation.getStatus())
                .changedBy(ChangedBy.OWNER)
                .reason(reason)
                .build());
    }
}
