package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.service.InquiryAccess;
import com.ilro.backend.domain.reservation.dto.AvailableSlotsResponse;
import com.ilro.backend.domain.reservation.dto.AvailableSlotsResponse.Day;
import com.ilro.backend.domain.reservation.dto.AvailableSlotsResponse.Slot;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.domain.reservation.exception.ReservationErrorCode;
import com.ilro.backend.domain.reservation.exception.SlotUnavailableReason;
import com.ilro.backend.domain.reservation.repository.ReservationRepository;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.util.KstTime;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.infra.map.GeoPoint;
import com.ilro.backend.infra.map.MapClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// 방문 가능 시간 (C-12, 명세 5.2, BE-09) - 화면에서 고른 시간은 잡아 둔 것이 아니며 확정(BE-11) 때 같은 검사를 다시 함
// 후보: 영업 시작부터 slot-step 간격으로 '시작 + 작업시간'이 영업 종료 안에 드는 시각
// 업체·문의 조건(1·2번)이나 이동시간 계산이 막히면 후보 대신 422, 문의는 사장님 확인으로
@Service
public class AvailableSlotService {

    private final InquiryAccess inquiryAccess;
    private final ReservationRepository reservationRepository;
    private final BookingEligibility eligibility;
    private final BookingEscalation escalation;
    private final OwnerScheduleReader scheduleReader;
    private final MapClient mapClient;
    private final Clock clock;
    private final Duration slotStep;

    public AvailableSlotService(InquiryAccess inquiryAccess, ReservationRepository reservationRepository,
                                BookingEligibility eligibility, BookingEscalation escalation,
                                OwnerScheduleReader scheduleReader, MapClient mapClient, Clock clock,
                                @Value("${app.booking.slot-step}") Duration slotStep) {
        this.inquiryAccess = inquiryAccess;
        this.reservationRepository = reservationRepository;
        this.eligibility = eligibility;
        this.escalation = escalation;
        this.scheduleReader = scheduleReader;
        this.mapClient = mapClient;
        this.clock = clock;
        this.slotStep = slotStep;
    }

    // from이 없으면 오늘(한국 날짜)부터
    @Transactional(readOnly = true)
    public AvailableSlotsResponse availableSlots(AuthPrincipal principal, Long inquiryId, LocalDate from, int days) {
        Inquiry inquiry = inquiryAccess.find(principal, inquiryId);
        // 종료 409·업체 이용 불가 403·필수 동의 없음 403
        inquiryAccess.checkWritable(inquiry);
        reservationRepository.findByInquiryIdAndStatusNot(inquiryId, ReservationStatus.CANCELED)
                .ifPresent(reservation -> {
                    throw new BusinessException(ReservationErrorCode.RESERVATION_ALREADY_EXISTS,
                            Collections.singletonMap("reservationId", reservation.getId()));
                });
        // 사장님이 직접 응대 중이거나 이미 사장님 확인·서비스 불가 단계면 자동 예약 안 함 (상태는 그대로)
        if (!inquiry.isAiAnswering()) {
            throw new BusinessException(ReservationErrorCode.OWNER_HANDLING);
        }
        Optional<BookingEligibility.Block> block = eligibility.check(inquiry);
        if (block.isPresent()) {
            throw askOwner(inquiryId, block.get().code(), block.get().result());
        }

        Instant now = clock.instant();
        LocalDate start = from != null ? from : LocalDate.now(clock.withZone(KstTime.ZONE));
        int workMinutes = inquiry.getWorkType().getDurationMinutes();
        GeoPoint destination = BookingEligibility.destination(inquiry).orElseThrow();
        TravelTimes travel = new TravelTimes(mapClient);

        List<Day> result = new ArrayList<>();
        for (ScheduleDay day : scheduleReader.read(inquiry.getOwner(), start, start.plusDays(days)).values()) {
            result.add(slotsOf(inquiryId, day, workMinutes, destination, now, travel));
        }
        return new AvailableSlotsResponse(workMinutes, result);
    }

    private Day slotsOf(Long inquiryId, ScheduleDay day, int workMinutes, GeoPoint destination, Instant now,
                        TravelTimes travel) {
        if (day.isClosed()) {
            return new Day(day.date(), List.of(), SlotUnavailableReason.OUTSIDE_BUSINESS_HOURS);
        }
        List<Slot> slots = new ArrayList<>();
        Map<SlotUnavailableReason, Integer> reasons = new EnumMap<>(SlotUnavailableReason.class);
        Instant close = day.at(day.close());
        Duration work = Duration.ofMinutes(workMinutes);
        for (Instant startAt = day.at(day.open()); !startAt.plus(work).isAfter(close);
             startAt = startAt.plus(slotStep)) {
            Instant endAt = startAt.plus(work);
            SlotCheck check = SlotChecker.check(day, startAt, workMinutes, destination, now, travel);
            if (check.travelUnknown()) {
                throw askOwner(inquiryId, ReservationErrorCode.TRAVEL_TIME_UNAVAILABLE, null);
            }
            if (check.isAvailable()) {
                slots.add(new Slot(KstTime.of(startAt), KstTime.of(endAt)));
            } else {
                reasons.merge(check.reason(), 1, Integer::sum);
            }
        }
        if (!slots.isEmpty()) {
            return new Day(day.date(), slots, null);
        }
        // 작업시간이 영업시간보다 길면 후보가 없음 - 영업시간 밖
        SlotUnavailableReason emptyReason = reasons.entrySet().stream()
                .max(Comparator.<Map.Entry<SlotUnavailableReason, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .orElse(SlotUnavailableReason.OUTSIDE_BUSINESS_HOURS);
        return new Day(day.date(), List.of(), emptyReason);
    }

    private BusinessException askOwner(Long inquiryId, ReservationErrorCode code, Object result) {
        escalation.askOwner(inquiryId, code);
        return result == null ? new BusinessException(code) : new BusinessException(code, result);
    }
}
