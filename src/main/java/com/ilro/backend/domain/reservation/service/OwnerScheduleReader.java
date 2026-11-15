package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.entity.OwnerBusinessHour;
import com.ilro.backend.domain.owner.repository.OwnerBlockRepository;
import com.ilro.backend.domain.owner.repository.OwnerBusinessHourRepository;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.domain.reservation.repository.ReservationRepository;
import com.ilro.backend.domain.reservation.service.ScheduleDay.TimeRange;
import com.ilro.backend.domain.reservation.service.ScheduleDay.Visit;
import com.ilro.backend.global.common.util.KstTime;
import com.ilro.backend.infra.map.GeoPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// 업체 일정 읽기 - 기간(한국 날짜 [from, to))의 영업시간·점심·한도·여유시간·차단·예약을 한 번에 읽어 날짜별로 나눔
// 설정 값은 읽는 시점의 현재 값 (작업시간·여유시간을 바꿔도 기존 예약은 그대로 - 예약에 당시 값이 있음)
@Component
@RequiredArgsConstructor
public class OwnerScheduleReader {

    private final OwnerBusinessHourRepository businessHourRepository;
    private final OwnerBlockRepository blockRepository;
    private final ReservationRepository reservationRepository;

    public Map<LocalDate, ScheduleDay> read(Owner owner, LocalDate from, LocalDate to) {
        Instant start = from.atStartOfDay(KstTime.ZONE).toInstant();
        Instant end = to.atStartOfDay(KstTime.ZONE).toInstant();
        Map<DayOfWeek, OwnerBusinessHour> hours = businessHourRepository
                .findByOwnerIdOrderByDayOfWeekAsc(owner.getId()).stream()
                .collect(Collectors.toMap(OwnerBusinessHour::getDayOfWeek, Function.identity()));
        List<TimeRange> blocks = blockRepository.findOverlapping(owner.getId(), start, end).stream()
                .map(block -> new TimeRange(block.getStartAt(), block.getEndAt()))
                .toList();
        List<Visit> visits = reservationRepository
                .findOverlapping(owner.getId(), start, end, ReservationStatus.CANCELED).stream()
                .map(r -> new Visit(r.getId(), r.getStartAt(), r.getEndAt(),
                        GeoPoint.of(r.getLat(), r.getLng()).orElse(null)))
                .toList();
        GeoPoint origin = GeoPoint.of(owner.getLat(), owner.getLng()).orElse(null);

        Map<LocalDate, ScheduleDay> days = new LinkedHashMap<>();
        for (LocalDate date = from; date.isBefore(to); date = date.plusDays(1)) {
            Instant dayStart = date.atStartOfDay(KstTime.ZONE).toInstant();
            Instant dayEnd = date.plusDays(1).atStartOfDay(KstTime.ZONE).toInstant();
            OwnerBusinessHour hour = hours.get(date.getDayOfWeek());
            boolean closed = hour == null || hour.isClosed();
            days.put(date, new ScheduleDay(date,
                    closed ? null : hour.getOpenTime(), closed ? null : hour.getCloseTime(),
                    owner.getLunchStart(), owner.getLunchEnd(), owner.getMaxDailyVisits(), owner.getBufferMinutes(),
                    origin,
                    blocks.stream().filter(block -> block.overlaps(dayStart, dayEnd)).toList(),
                    visits.stream().filter(visit -> visit.overlaps(dayStart, dayEnd)).toList()));
        }
        return days;
    }
}
