package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.customer.entity.Customer;
import com.ilro.backend.domain.customer.repository.CustomerRepository;
import com.ilro.backend.domain.notification.entity.Notification;
import com.ilro.backend.domain.notification.entity.NotificationEventType;
import com.ilro.backend.domain.notification.entity.NotificationRefType;
import com.ilro.backend.domain.notification.repository.NotificationRepository;
import com.ilro.backend.domain.notification.service.ReservationNotifier;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.entity.OwnerWorkType;
import com.ilro.backend.domain.owner.repository.OwnerWorkTypeRepository;
import com.ilro.backend.domain.reservation.dto.ManualReservationRequest;
import com.ilro.backend.domain.reservation.dto.ManualReservationResponse;
import com.ilro.backend.domain.reservation.entity.ChangedBy;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationHistory;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.domain.reservation.exception.ReservationErrorCode;
import com.ilro.backend.domain.reservation.exception.SlotUnavailableReason;
import com.ilro.backend.domain.reservation.repository.ReservationHistoryRepository;
import com.ilro.backend.domain.reservation.repository.ReservationRepository;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.common.util.PhoneNumbers;
import com.ilro.backend.global.common.util.RequestFingerprint;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import com.ilro.backend.global.exception.ConstraintErrorMapper;
import com.ilro.backend.infra.map.GeoPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// 사장님 수동 예약 (B-18, 명세 1.6·1.7·4.6·5.2, BE-12) - 전화 등으로 받은 예약을 직접 등록, 처음부터 CONFIRMED
// 자동 예약과 같은 시간 검사기(5.2 3번)를 쓰고, 업체를 잠근 뒤 DB만으로 다시 검사하고 저장 (ScheduleWriter)
// 영업시간 밖: 경고(409 SLOT_UNAVAILABLE OUTSIDE_BUSINESS_HOURS)를 확인하고 acknowledgeOutsideHours: true로 다시 보내면 등록
//   (명세 7.2 결정 7) - 지난 시각·점심·차단·하루 한도·겹침·이동/여유시간 부족은 예외 없음
// 이동시간을 셀 수 없으면 422 TRAVEL_TIME_UNAVAILABLE - 시간을 임의로 만들지 않음 (사장님 화면이라 문의 상태는 상관없음)
// 서비스 지역·자동 예약 설정은 보지 않음 (사장님이 직접 받은 예약)
// 같은 Idempotency-Key·같은 요청이면 처음 결과를 200, 키는 같은데 내용이 다르면 409 IDEMPOTENCY_KEY_REUSED
@Service
@RequiredArgsConstructor
public class ManualReservationService {

    private static final String PATH = "/api/v1/owner/reservations";
    private static final String IDEMPOTENCY_CONSTRAINT = "uq_reservation_idempotency_key";

    private final ScheduleWriter writer;
    private final VisitScheduleService visitSchedule;
    private final ReservationRepository reservationRepository;
    private final ReservationHistoryRepository historyRepository;
    private final OwnerWorkTypeRepository workTypeRepository;
    private final CustomerRepository customerRepository;
    private final NotificationRepository notificationRepository;
    private final ReservationNotifier notifier;

    // created: 새로 저장했으면 true(201), 같은 요청을 다시 받았으면 false(200)
    public record Outcome(ManualReservationResponse response, boolean created) {
    }

    public Outcome create(Long ownerId, String idempotencyKey, ManualReservationRequest request) {
        String key = RequestFingerprint.idempotencyKey(idempotencyKey);
        String phone = PhoneNumbers.normalize(request.customerPhone());
        if (!PhoneNumbers.isMobile(phone)) {
            throw invalid("customerPhone", "휴대전화 번호 형식이 아닙니다");
        }
        Instant startAt = request.startAt().toInstant();
        if (startAt.getEpochSecond() % 60 != 0 || startAt.getNano() != 0) {
            throw invalid("startAt", "분 단위여야 합니다");
        }
        String hash = RequestFingerprint.of(PATH, "OWNER:" + ownerId, canonical(request, phone, startAt));

        Optional<Outcome> replayed = writer.read(() -> replay(key, hash));
        if (replayed.isPresent()) {
            return replayed.get();
        }
        try {
            return writer.write(ownerId,
                    answers -> {
                        check(ownerId, null, request, startAt, answers);
                        return null;
                    },
                    (owner, ignored, answers) -> {
                        Optional<Outcome> again = replay(key, hash);
                        if (again.isPresent()) {
                            return again.get();
                        }
                        Checked checked = check(ownerId, owner, request, startAt, answers);
                        return save(owner, checked, request, phone, startAt, key, hash);
                    });
        } catch (DataIntegrityViolationException e) {
            // 같은 키의 두 요청이 동시에 저장하려 함 - 먼저 저장된 쪽을 다시 읽어 같은 규칙으로 답함
            if (IDEMPOTENCY_CONSTRAINT.equals(ConstraintErrorMapper.map(e).constraintName())) {
                return writer.read(() -> replay(key, hash)).orElseThrow(() -> e);
            }
            throw e;
        }
    }

    private record Checked(OwnerWorkType workType, SlotCheck slot, boolean outsideBusinessHours) {
    }

    // owner가 null이면 잠그기 전 검사 (업체를 새로 읽음)
    private Checked check(Long ownerId, Owner lockedOwner, ManualReservationRequest request, Instant startAt,
                          ScheduleWriter.MapAnswers answers) {
        OwnerWorkType workType = workTypeRepository.findById(request.workTypeId())
                .filter(found -> found.getOwner().getId().equals(ownerId) && found.isActive())
                .orElseThrow(() -> invalid("workTypeId", "제공 중인 작업 유형이 아닙니다"));
        Owner owner = lockedOwner != null ? lockedOwner : workType.getOwner();
        int workMinutes = workType.getDurationMinutes();
        GeoPoint destination = GeoPoint.of(request.lat(), request.lng())
                .orElseThrow(() -> invalid("lat", "좌표가 필요합니다"));
        SlotCheck slot = visitSchedule.check(owner, startAt, workMinutes, destination, null, answers.travel());
        boolean outside = false;
        if (slot.reason() == SlotUnavailableReason.OUTSIDE_BUSINESS_HOURS && request.acknowledgeOutsideHours()) {
            outside = true;
            slot = visitSchedule.check(owner, startAt, workMinutes, destination, null, answers.travel(), true);
        }
        if (slot.travelUnknown()) {
            throw new BusinessException(ReservationErrorCode.TRAVEL_TIME_UNAVAILABLE);
        }
        if (!slot.isAvailable()) {
            throw new BusinessException(ReservationErrorCode.SLOT_UNAVAILABLE, Map.of("reason", slot.reason()));
        }
        return new Checked(workType, slot, outside);
    }

    private Outcome save(Owner owner, Checked checked, ManualReservationRequest request, String phone,
                         Instant startAt, String key, String hash) {
        customerRepository.insertIfAbsent(owner.getId(), phone);
        Customer customer = customerRepository.findByOwnerIdAndPhone(owner.getId(), phone).orElseThrow();
        if (request.customerName() != null && !request.customerName().isBlank()) {
            customer.changeName(request.customerName().trim());
        }
        int workMinutes = checked.workType().getDurationMinutes();
        Reservation reservation = reservationRepository.saveAndFlush(Reservation.builder()
                .owner(owner)
                .customer(customer)
                .startAt(startAt)
                .endAt(startAt.plus(Duration.ofMinutes(workMinutes)))
                .address(request.address().trim())
                .lat(request.lat())
                .lng(request.lng())
                .workType(checked.workType())
                .travelTimeMinutes(checked.slot().travelMinutes())
                .workMinutes(workMinutes)
                .bufferMinutes(owner.getBufferMinutes())
                .isManual(true)
                .idempotencyKey(key)
                .idempotencyHash(hash)
                .status(ReservationStatus.CONFIRMED)
                .build());
        historyRepository.save(ReservationHistory.builder()
                .reservation(reservation)
                .toStatus(ReservationStatus.CONFIRMED)
                .changedBy(ChangedBy.OWNER)
                .build());
        List<Notification> notifications = notifier.manuallyBooked(reservation);
        return new Outcome(ManualReservationResponse.of(reservation, checked.outsideBusinessHours(), notifications),
                true);
    }

    // 같은 키로 저장한 예약 - 지문이 같으면 그 예약의 지금 상태를 200으로 (영업시간 밖 여부는 지금 영업시간으로 다시 봄)
    private Optional<Outcome> replay(String key, String hash) {
        return reservationRepository.findByIdempotencyKey(key).map(reservation -> {
            if (!hash.equals(reservation.getIdempotencyHash())) {
                throw new BusinessException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            List<Notification> notifications = notificationRepository.findByRefTypeAndRefIdAndEventTypeOrderByIdAsc(
                    NotificationRefType.RESERVATION, reservation.getId(), NotificationEventType.RESERVATION_CONFIRMED);
            boolean outside = !visitSchedule.withinBusinessHours(reservation.getOwner(), reservation.getStartAt(),
                    reservation.getEndAt());
            return new Outcome(ManualReservationResponse.of(reservation, outside, notifications), false);
        });
    }

    // 요청 지문용 본문 - 키 이름순, 번호는 숫자만, 시각은 UTC
    private static String canonical(ManualReservationRequest request, String phone, Instant startAt) {
        return "{\"acknowledgeOutsideHours\":" + request.acknowledgeOutsideHours()
                + ",\"address\":" + quote(request.address().trim())
                + ",\"customerName\":" + quote(request.customerName() == null ? null : request.customerName().trim())
                + ",\"customerPhone\":" + quote(phone)
                + ",\"lat\":" + request.lat().stripTrailingZeros().toPlainString()
                + ",\"lng\":" + request.lng().stripTrailingZeros().toPlainString()
                + ",\"startAt\":" + quote(startAt.toString())
                + ",\"workTypeId\":" + request.workTypeId() + "}";
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(CommonErrorCode.INVALID_INPUT, List.of(new FieldErrorResponse(field, reason)));
    }
}
