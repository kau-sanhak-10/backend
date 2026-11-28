package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.customer.entity.Customer;
import com.ilro.backend.domain.owner.dto.OwnerBlockResponse;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.repository.OwnerBlockRepository;
import com.ilro.backend.domain.owner.repository.OwnerRepository;
import com.ilro.backend.domain.reservation.dto.OwnerScheduleResponse;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationChange;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.domain.reservation.repository.ReservationChangeRepository;
import com.ilro.backend.domain.reservation.repository.ReservationRepository;
import com.ilro.backend.domain.reservation.service.VisitTimeline.DayPlan;
import com.ilro.backend.domain.reservation.service.VisitTimeline.PlannedVisit;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.common.util.KstTime;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// 사장님 일정 보기 (B-17·B-28, BE-12) - 기간(한국 날짜 from~to, 둘 다 포함)의 날짜별 예약·차단 시간·이동/여유/작업시간
// 읽기만 함 - 이동시간은 지도 장애도 '모름'(UNKNOWN)으로 보여 주고 저장하지 않음 (MapLookup.newDisplayRequest)
// 취소 예약은 빼고, 완료 예약은 그날 동선이라 보여 줌
@Service
@RequiredArgsConstructor
public class OwnerScheduleService {

    // 한 번에 볼 수 있는 기간 (일) - 하루마다 지도 이동시간을 셈
    static final int MAX_RANGE_DAYS = 31;

    private final ScheduleWriter writer;
    private final OwnerRepository ownerRepository;
    private final OwnerScheduleReader scheduleReader;
    private final OwnerBlockRepository blockRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationChangeRepository changeRepository;
    private final MapLookup mapLookup;

    public OwnerScheduleResponse schedule(Long ownerId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw invalid("to", "from보다 앞설 수 없습니다");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw invalid("to", MAX_RANGE_DAYS + "일까지 볼 수 있습니다");
        }
        LocalDate end = to.plusDays(1);
        Instant start = from.atStartOfDay(KstTime.ZONE).toInstant();
        Instant until = end.atStartOfDay(KstTime.ZONE).toInstant();
        return writer.read(() -> {
            Owner owner = ownerRepository.findById(ownerId)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
            Map<LocalDate, ScheduleDay> days = scheduleReader.read(owner, from, end);
            Map<Long, Reservation> reservations = reservationRepository
                    .findOverlapping(ownerId, start, until, ReservationStatus.CANCELED).stream()
                    .collect(Collectors.toMap(Reservation::getId, Function.identity()));
            Map<Long, ReservationChange> activeChanges = reservations.isEmpty() ? Map.of()
                    : changeRepository.findByReservationIdInAndStatusIn(reservations.keySet(),
                            CustomerReservationService.ACTIVE_CHANGE).stream()
                    .collect(Collectors.toMap(change -> change.getReservation().getId(), Function.identity()));
            List<OwnerBlockResponse> blocks = blockRepository.findOverlapping(ownerId, start, until).stream()
                    .map(OwnerBlockResponse::from)
                    .toList();
            TravelTimes travel = mapLookup.newDisplayRequest();

            List<OwnerScheduleResponse.Day> result = new ArrayList<>();
            for (ScheduleDay day : days.values()) {
                DayPlan plan = VisitTimeline.plan(day, travel);
                Instant dayStart = day.date().atStartOfDay(KstTime.ZONE).toInstant();
                Instant dayEnd = day.date().plusDays(1).atStartOfDay(KstTime.ZONE).toInstant();
                result.add(new OwnerScheduleResponse.Day(day.date(), day.isClosed(), day.open(), day.close(),
                        day.lunchStart(), day.lunchEnd(), day.maxDailyVisits(), plan.visits().size(),
                        plan.needsCheck(),
                        blocks.stream()
                                .filter(block -> block.startAt().toInstant().isBefore(dayEnd)
                                        && block.endAt().toInstant().isAfter(dayStart))
                                .toList(),
                        plan.visits().stream()
                                // 일정과 예약을 따로 읽는 사이에 취소된 예약은 뺌 (다음 조회에는 일정에서도 빠짐)
                                .filter(visit -> reservations.containsKey(visit.reservationId()))
                                .map(visit -> visit(visit, reservations.get(visit.reservationId()),
                                        activeChanges.get(visit.reservationId())))
                                .toList()));
            }
            return new OwnerScheduleResponse(from, to, result);
        });
    }

    private static OwnerScheduleResponse.Visit visit(PlannedVisit planned, Reservation reservation,
                                                     ReservationChange change) {
        Customer customer = reservation.getCustomer();
        return new OwnerScheduleResponse.Visit(reservation.getId(), reservation.getStatus(),
                KstTime.of(planned.startAt()), KstTime.of(planned.endAt()), planned.workMinutes(),
                reservation.isManual(),
                reservation.getWorkType() == null ? null : reservation.getWorkType().getWorkCategory(),
                reservation.getInquiry() == null ? null : reservation.getInquiry().getId(),
                customer == null ? null : customer.getName(), customer == null ? null : customer.getPhone(),
                reservation.getAddress(), planned.previousReservationId(), planned.travelMinutes(),
                planned.travelDistanceMeters(), planned.bufferMinutes(), planned.slackMinutes(), planned.travelStatus(),
                change == null ? null : new OwnerScheduleResponse.ActiveChange(change.getId(), change.getStatus(),
                        change.getRequestedBy(), KstTime.of(change.getExpiresAt())));
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(CommonErrorCode.INVALID_INPUT, List.of(new FieldErrorResponse(field, reason)));
    }
}
