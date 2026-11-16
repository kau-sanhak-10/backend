package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.auth.exception.AuthErrorCode;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.entity.InquiryStatus;
import com.ilro.backend.domain.inquiry.service.InquiryAccess;
import com.ilro.backend.domain.notification.entity.Notification;
import com.ilro.backend.domain.notification.entity.NotificationEventType;
import com.ilro.backend.domain.notification.entity.NotificationRefType;
import com.ilro.backend.domain.notification.repository.NotificationRepository;
import com.ilro.backend.domain.notification.service.ReservationNotifier;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.repository.OwnerRepository;
import com.ilro.backend.domain.reservation.dto.ReservationConfirmRequest;
import com.ilro.backend.domain.reservation.dto.ReservationConfirmResponse;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.domain.reservation.exception.ReservationErrorCode;
import com.ilro.backend.domain.reservation.exception.SlotUnavailableReason;
import com.ilro.backend.domain.reservation.repository.ReservationRepository;
import com.ilro.backend.domain.reservation.service.BookingEligibility.ServiceAreaJudge;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.common.util.RequestFingerprint;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import com.ilro.backend.global.exception.ConstraintErrorMapper;
import com.ilro.backend.infra.map.GeoPoint;
import com.ilro.backend.infra.map.ServiceAreaMatch;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

// 고객 예약 최종 확정 (C-13, 명세 5.2, BE-11) - 후보 조회(BE-09)와 같은 검사를 처음부터 다시 하고 저장
// 순서 (명세 1.6·1.7·5.2)
//   1) 같은 Idempotency-Key로 이미 저장한 예약이 있으면 지문이 같을 때 처음 결과를 200, 다르면 409 IDEMPOTENCY_KEY_REUSED
//   2) 잠그기 전 검사 - 업체·문의(1·2번)·시간(3번)·번호 인증(4번). 지도는 여기서만 부름
//   3) 업체 행을 잠근 뒤(3초 넘으면 503) 같은 검사를 DB만으로 다시 - 지도 답은 2)에서 받은 것을 다시 씀
//      잠그기 전과 앞뒤 방문지가 달라져 처음 보는 이동 구간이 생기면 409 SLOT_UNAVAILABLE(TRAVEL_CONFLICT) - 다른 시간 고르기
//   4) CONFIRMED 예약 저장 + 알림 PENDING 행(고객 문자·사장님 푸시) + 문의 BOOKED, 201
// 1·2번이나 이동시간을 셀 수 없어 막히면(3.5의 422) 트랜잭션을 되돌린 뒤 문의를 사장님 확인으로 넘김 (BookingEscalation)
// 지도 일시 장애는 503 MAP_UNAVAILABLE - 문의 그대로
@Service
public class ReservationConfirmService {

    private static final String PATH = "/api/v1/customer/inquiries/%d/reservations";
    private static final String IDEMPOTENCY_CONSTRAINT = "uq_reservation_idempotency_key";

    private final InquiryAccess inquiryAccess;
    private final ReservationRepository reservationRepository;
    private final OwnerRepository ownerRepository;
    private final NotificationRepository notificationRepository;
    private final BookingEligibility eligibility;
    private final BookingEscalation escalation;
    private final VisitScheduleService visitSchedule;
    private final MapLookup mapLookup;
    private final ReservationNotifier notifier;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    public ReservationConfirmService(InquiryAccess inquiryAccess, ReservationRepository reservationRepository,
                                     OwnerRepository ownerRepository, NotificationRepository notificationRepository,
                                     BookingEligibility eligibility, BookingEscalation escalation,
                                     VisitScheduleService visitSchedule, MapLookup mapLookup,
                                     ReservationNotifier notifier, PlatformTransactionManager transactionManager) {
        this.inquiryAccess = inquiryAccess;
        this.reservationRepository = reservationRepository;
        this.ownerRepository = ownerRepository;
        this.notificationRepository = notificationRepository;
        this.eligibility = eligibility;
        this.escalation = escalation;
        this.visitSchedule = visitSchedule;
        this.mapLookup = mapLookup;
        this.notifier = notifier;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    // created: 새로 저장했으면 true(201), 같은 요청을 다시 받았으면 false(200)
    public record Outcome(ReservationConfirmResponse response, boolean created) {
    }

    public Outcome confirm(AuthPrincipal principal, Long inquiryId, String idempotencyKey,
                           ReservationConfirmRequest request) {
        String key = RequestFingerprint.idempotencyKey(idempotencyKey);
        Instant startAt = startAtOf(request);
        String hash = RequestFingerprint.of(PATH.formatted(inquiryId), "INQUIRY:" + inquiryId,
                "{\"startAt\":\"" + startAt + "\"}");

        Optional<Outcome> replayed = readTx.execute(status -> {
            inquiryAccess.find(principal, inquiryId);
            return replay(key, hash);
        });
        if (replayed.isPresent()) {
            return replayed.get();
        }
        Precheck precheck = escalating(inquiryId, () -> readTx.execute(status -> precheck(principal, inquiryId, startAt)));
        try {
            return escalating(inquiryId, () -> writeTx.execute(
                    status -> confirmLocked(principal, inquiryId, startAt, key, hash, precheck)));
        } catch (DataIntegrityViolationException e) {
            // 같은 키의 두 요청이 동시에 저장하려 함 - 먼저 저장된 쪽을 새 트랜잭션에서 읽어 같은 규칙으로 답함
            if (IDEMPOTENCY_CONSTRAINT.equals(ConstraintErrorMapper.map(e).constraintName())) {
                return readTx.execute(status -> replay(key, hash)).orElseThrow(() -> e);
            }
            throw e;
        }
    }

    // 잠그기 전 검사 결과 - 잠근 뒤에는 여기서 받은 지도 답만 씀
    private record Precheck(Long ownerId, AskedAreas areas, TravelTimes travel) {
    }

    private Precheck precheck(AuthPrincipal principal, Long inquiryId, Instant startAt) {
        Inquiry inquiry = inquiryAccess.find(principal, inquiryId);
        AskedAreas areas = new AskedAreas(mapLookup::serviceArea);
        TravelTimes travel = visitSchedule.newTravelRequest();
        SlotCheck check = check(inquiry, startAt, areas, travel);
        if (check.travelUnknown()) {
            throw new AskOwner(ReservationErrorCode.TRAVEL_TIME_UNAVAILABLE, null);
        }
        requireSlot(check);
        requirePhoneVerified(inquiry);
        return new Precheck(inquiry.getOwner().getId(), areas, travel);
    }

    private Outcome confirmLocked(AuthPrincipal principal, Long inquiryId, Instant startAt, String key, String hash,
                                  Precheck precheck) {
        // 업체를 먼저 잠금 (명세 1.7) - 같은 업체의 다른 예약·일정 쓰기는 여기서 차례를 기다림
        Owner owner = ownerRepository.findByIdForUpdate(precheck.ownerId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        // 기다리는 동안 같은 키 요청이 먼저 저장했으면 그 결과
        Optional<Outcome> replayed = replay(key, hash);
        if (replayed.isPresent()) {
            return replayed.get();
        }
        Inquiry inquiry = inquiryAccess.lock(principal, inquiryId);
        SlotCheck check;
        try {
            check = check(inquiry, startAt, precheck.areas().askedOnly(), precheck.travel().askedOnly());
        } catch (TravelTimes.NotAskedException | AskedAreas.NotAskedException e) {
            throw slotUnavailable(SlotUnavailableReason.TRAVEL_CONFLICT);
        }
        // 잠그기 전에는 셀 수 있던 시각이 앞뒤 방문지가 바뀌어 셀 수 없는 구간에 걸림 - 다른 시간 고르기
        if (check.travelUnknown()) {
            throw slotUnavailable(SlotUnavailableReason.TRAVEL_CONFLICT);
        }
        requireSlot(check);
        requirePhoneVerified(inquiry);

        int workMinutes = inquiry.getWorkType().getDurationMinutes();
        Reservation reservation = reservationRepository.saveAndFlush(Reservation.builder()
                .inquiry(inquiry)
                .owner(owner)
                .customer(inquiry.getCustomer())
                .startAt(startAt)
                .endAt(startAt.plus(Duration.ofMinutes(workMinutes)))
                .address(inquiry.getAddress())
                .lat(inquiry.getLat())
                .lng(inquiry.getLng())
                .workType(inquiry.getWorkType())
                .travelTimeMinutes(check.travelMinutes())
                .workMinutes(workMinutes)
                .bufferMinutes(owner.getBufferMinutes())
                .isManual(false)
                .idempotencyKey(key)
                .idempotencyHash(hash)
                .status(ReservationStatus.CONFIRMED)
                .build());
        List<Notification> notifications = notifier.confirmed(reservation);
        int versionBefore = inquiry.getVersion();
        inquiry.changeStatus(InquiryStatus.BOOKED);
        inquiryAccess.bumpVersion(inquiry, versionBefore);
        return new Outcome(ReservationConfirmResponse.of(reservation, notifications), true);
    }

    // 잠그기 전·뒤 공통 검사 (명세 5.2 1~3번) - 시간 검사 결과를 돌려주고 판단은 부르는 쪽
    private SlotCheck check(Inquiry inquiry, Instant startAt, ServiceAreaJudge areas, TravelTimes travel) {
        // 종료 409·업체 이용 불가 403·필수 동의 없음 403
        inquiryAccess.checkWritable(inquiry);
        // 4번 중 활성 예약 - 예약이 잡히면 문의가 BOOKED라 '사장님 응대'보다 먼저 봐야 기존 예약을 보여 줄 수 있음
        reservationRepository.findByInquiryIdAndStatusNot(inquiry.getId(), ReservationStatus.CANCELED)
                .ifPresent(reservation -> {
                    throw new BusinessException(ReservationErrorCode.RESERVATION_ALREADY_EXISTS,
                            Collections.singletonMap("reservationId", reservation.getId()));
                });
        // 사장님이 직접 응대 중이거나 이미 사장님 확인·서비스 불가 단계면 자동 예약 안 함 (상태는 그대로)
        if (!inquiry.isAiAnswering()) {
            throw new BusinessException(ReservationErrorCode.OWNER_HANDLING);
        }
        Optional<BookingEligibility.Block> block = eligibility.check(inquiry, areas);
        if (block.isPresent()) {
            throw new AskOwner(block.get().code(), block.get().result());
        }
        int workMinutes = inquiry.getWorkType().getDurationMinutes();
        GeoPoint destination = BookingEligibility.destination(inquiry).orElseThrow();
        return visitSchedule.check(inquiry.getOwner(), startAt, workMinutes, destination, null, travel);
    }

    private static void requireSlot(SlotCheck check) {
        if (!check.isAvailable()) {
            throw slotUnavailable(check.reason());
        }
    }

    // 4번 - 방문정보 번호로 받은 문자 인증 (CUSTOMER_VERIFY, BE-18). 번호를 바꾸면 지워짐
    private static void requirePhoneVerified(Inquiry inquiry) {
        if (inquiry.getPhoneVerifiedAt() == null || inquiry.getCustomer() == null) {
            throw new BusinessException(AuthErrorCode.PHONE_NOT_VERIFIED);
        }
    }

    private static BusinessException slotUnavailable(SlotUnavailableReason reason) {
        return new BusinessException(ReservationErrorCode.SLOT_UNAVAILABLE, Map.of("reason", reason));
    }

    // 같은 키로 저장한 예약 - 지문이 같으면 그 예약의 지금 상태와 확정 알림을 200으로
    private Optional<Outcome> replay(String key, String hash) {
        return reservationRepository.findByIdempotencyKey(key).map(reservation -> {
            if (!hash.equals(reservation.getIdempotencyHash())) {
                throw new BusinessException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            List<Notification> notifications = notificationRepository.findByRefTypeAndRefIdAndEventTypeOrderByIdAsc(
                    NotificationRefType.RESERVATION, reservation.getId(), NotificationEventType.RESERVATION_CONFIRMED);
            return new Outcome(ReservationConfirmResponse.of(reservation, notifications), false);
        });
    }

    // 후보는 분 단위 - 초가 붙은 시각은 받지 않음
    private static Instant startAtOf(ReservationConfirmRequest request) {
        Instant startAt = request.startAt().toInstant();
        if (startAt.getEpochSecond() % 60 != 0 || startAt.getNano() != 0) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    List.of(new FieldErrorResponse("startAt", "분 단위여야 합니다")));
        }
        return startAt;
    }

    // 422로 막힘 - 트랜잭션이 끝난(되돌린) 뒤 문의를 사장님 확인으로 넘기고 응답
    // (잠근 트랜잭션 안에서 넘기면 같은 문의 행을 두 트랜잭션이 잠가 멈춤)
    private <T> T escalating(Long inquiryId, Supplier<T> work) {
        try {
            return work.get();
        } catch (AskOwner e) {
            escalation.askOwner(inquiryId, e.code);
            throw e.result == null ? new BusinessException(e.code) : new BusinessException(e.code, e.result);
        }
    }

    private static final class AskOwner extends RuntimeException {

        private final ReservationErrorCode code;
        private final transient Object result;

        AskOwner(ReservationErrorCode code, Object result) {
            super(code.getCode(), null, false, false);
            this.code = code;
            this.result = result;
        }
    }

    // 서비스 지역 답을 기억 - 잠근 뒤에는 기억한 답만 (지도를 부르지 않음)
    static final class AskedAreas implements ServiceAreaJudge {

        private final ServiceAreaJudge source;
        private final Map<List<Object>, ServiceAreaMatch> asked = new HashMap<>();

        AskedAreas(ServiceAreaJudge source) {
            this.source = source;
        }

        @Override
        public ServiceAreaMatch match(GeoPoint point, List<String> regionCodes) {
            return asked.computeIfAbsent(List.of(point, List.copyOf(regionCodes)),
                    key -> source.match(point, regionCodes));
        }

        AskedAreas askedOnly() {
            Map<List<Object>, ServiceAreaMatch> known = Map.copyOf(asked);
            return new AskedAreas((point, regionCodes) -> {
                ServiceAreaMatch match = known.get(List.of(point, List.copyOf(regionCodes)));
                if (match == null) {
                    throw new NotAskedException();
                }
                return match;
            });
        }

        // 잠그는 사이 방문 주소나 서비스 지역이 바뀜 - 다시 고르게 함
        static final class NotAskedException extends RuntimeException {

            NotAskedException() {
                super("잠그기 전에 확인하지 않은 서비스 지역", null, false, false);
            }
        }
    }
}
