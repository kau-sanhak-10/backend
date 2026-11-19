package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.notification.service.ReservationNotifier;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.reservation.dto.ReservationCancelRequest;
import com.ilro.backend.domain.reservation.dto.ReservationCancelResponse;
import com.ilro.backend.domain.reservation.dto.ReservationSnapshot;
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
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

// 예약 즉시 취소 (C-16·B-20, 명세 5.4, BE-12) - 승인 단계 없이 바로, 변경 요청을 만들지 않음
// 업체를 잠근 한 트랜잭션에서: 예약 CANCELED·version +1·이력·진행 중 변경 CLOSED(RESERVATION_CANCELED)·알림 PENDING
// 이미 취소됨 200 alreadyCanceled (이력·알림 더 안 만듦) / 완료 409 RESERVATION_COMPLETED + 업체 연락처 / 버전 다름 409 + 최신 예약
// 변경 수락과 동시에 오면 먼저 끝난 쪽이 버전을 올려 뒤의 요청은 버전 충돌 - 취소된 예약이 다시 살아나지 않음
// 연결된 문의 상태는 그대로 둠 (다시 예약하려면 사장님이 수동 예약)
@Service
@RequiredArgsConstructor
public class ReservationCancelService {

    private final ScheduleWriter writer;
    private final ReservationRepository reservationRepository;
    private final ReservationChangeRepository changeRepository;
    private final ReservationHistoryRepository historyRepository;
    private final CustomerReservationAccess customerAccess;
    private final ReservationNotifier notifier;
    private final Clock clock;

    // B-20 - 사유 필수
    public ReservationCancelResponse cancelByOwner(Long ownerId, Long reservationId, ReservationCancelRequest request) {
        if (request.reason() == null || request.reason().isBlank()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    List.of(new FieldErrorResponse("reason", "필수 값입니다")));
        }
        return writer.locked(ownerId, owner -> cancel(owner, reservationRepository.findByIdAndOwnerId(reservationId,
                        ownerId).orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND)),
                request, ChangedBy.OWNER));
    }

    // C-16 - 문의 토큰(그 문의 예약) 또는 예약 조회 고객 토큰(그 번호·그 업체 예약)
    public ReservationCancelResponse cancelByCustomer(AuthPrincipal principal, Long reservationId,
                                                      ReservationCancelRequest request) {
        Long ownerId = writer.read(() -> customerAccess.find(principal, reservationId).getOwner().getId());
        return writer.locked(ownerId, owner -> cancel(owner, customerAccess.find(principal, reservationId), request,
                ChangedBy.CUSTOMER));
    }

    private ReservationCancelResponse cancel(Owner owner, Reservation reservation, ReservationCancelRequest request,
                                             ChangedBy by) {
        if (reservation.getStatus() == ReservationStatus.CANCELED) {
            return new ReservationCancelResponse(reservation.getId(), ReservationStatus.CANCELED, true, List.of());
        }
        if (reservation.getStatus() == ReservationStatus.COMPLETED) {
            String contactPhone = owner.getContactPhone() != null ? owner.getContactPhone() : owner.getPhone();
            throw new BusinessException(ReservationErrorCode.RESERVATION_COMPLETED,
                    Collections.singletonMap("contactPhone", contactPhone));
        }
        checkVersion(reservation, request.version());

        Instant now = clock.instant();
        ReservationStatus from = reservation.getStatus();
        reservation.cancel();
        historyRepository.save(ReservationHistory.builder()
                .reservation(reservation)
                .fromStatus(from)
                .toStatus(ReservationStatus.CANCELED)
                .changedBy(by)
                .reason(blankToNull(request.reason()))
                .build());
        List<ReservationChange> active = changeRepository.findByReservationIdAndStatusIn(reservation.getId(),
                CustomerReservationService.ACTIVE_CHANGE);
        active.forEach(change -> change.close(ReservationChangeCloseReason.RESERVATION_CANCELED, by, now));
        notifier.canceled(reservation, by);
        reservationRepository.flush();
        return new ReservationCancelResponse(reservation.getId(), reservation.getStatus(), false,
                active.stream().map(ReservationChange::getId).toList());
    }

    // 화면이 본 버전과 다르면 덮어쓰지 않고 최신 예약을 돌려줌 (명세 1.7)
    static void checkVersion(Reservation reservation, Integer version) {
        if (!Objects.equals(reservation.getVersion(), version)) {
            throw new BusinessException(ReservationErrorCode.RESERVATION_VERSION_CONFLICT,
                    Collections.singletonMap("reservation", ReservationSnapshot.from(reservation)));
        }
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
