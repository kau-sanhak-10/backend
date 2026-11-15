package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.reservation.exception.SlotUnavailableReason;
import com.ilro.backend.domain.reservation.service.ScheduleDay.Visit;
import com.ilro.backend.infra.map.GeoPoint;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.OptionalInt;

// 방문 시각 검사기 (명세 5.2 3번) - 후보 계산(C-12)과 예약 확정(BE-11)이 같은 검사를 씀
// 방문 시간 = 시작 + 작업시간, 그 앞뒤로 이동시간 + 여유시간이 따로 들어감 (작업시간에 섞지 않음, BE-09)
// SlotUnavailableReason 순서대로 보고 처음 걸린 이유 하나를 돌려줌
public final class SlotChecker {

    private SlotChecker() {
    }

    public static SlotCheck check(ScheduleDay day, Instant start, int workMinutes, GeoPoint destination,
                                  Instant now, TravelTimes travel) {
        Instant end = start.plus(Duration.ofMinutes(workMinutes));
        if (start.isBefore(now)) {
            return SlotCheck.unavailable(SlotUnavailableReason.PAST);
        }
        if (day.isClosed() || start.isBefore(day.at(day.open())) || end.isAfter(day.at(day.close()))) {
            return SlotCheck.unavailable(SlotUnavailableReason.OUTSIDE_BUSINESS_HOURS);
        }
        if (day.hasLunch() && new ScheduleDay.TimeRange(day.at(day.lunchStart()), day.at(day.lunchEnd()))
                .overlaps(start, end)) {
            return SlotCheck.unavailable(SlotUnavailableReason.LUNCH_BREAK);
        }
        if (day.blocks().stream().anyMatch(block -> block.overlaps(start, end))) {
            return SlotCheck.unavailable(SlotUnavailableReason.BLOCKED);
        }
        if (day.maxDailyVisits() != null && day.visitsStartingToday() >= day.maxDailyVisits()) {
            return SlotCheck.unavailable(SlotUnavailableReason.DAILY_LIMIT_REACHED);
        }
        if (day.visits().stream().anyMatch(visit -> visit.overlaps(start, end))) {
            return SlotCheck.unavailable(SlotUnavailableReason.OVERLAP);
        }
        return checkTravel(day, start, end, destination, travel);
    }

    // 앞 방문(없으면 영업 시작 시각의 출발지)에서 끝난 뒤 여유시간 + 이동시간 안에 도착하고
    // 끝난 뒤 여유시간 + 이동시간 안에 다음 방문지에 갈 수 있어야 함
    // 출발지가 없는 업체는 첫 방문의 출발지 이동을 보지 않음 (자동 예약은 출발지가 있어야 켜짐 - AutoBookingSetup)
    // 차단 시간은 위치를 모르므로 겹침만 봄
    private static SlotCheck checkTravel(ScheduleDay day, Instant start, Instant end, GeoPoint destination,
                                         TravelTimes travel) {
        Optional<Visit> previous = day.visits().stream()
                .filter(visit -> !visit.endAt().isAfter(start))
                .max(Comparator.comparing(Visit::endAt));
        Optional<Visit> next = day.visits().stream()
                .filter(visit -> !visit.startAt().isBefore(end))
                .min(Comparator.comparing(Visit::startAt));

        Integer travelBefore = null;
        // 앞 방문의 좌표가 없을 때 출발지로 바꿔 계산하지 않음 (Optional.map은 null을 빈 값으로 만듦)
        GeoPoint from = previous.isPresent() ? previous.get().location() : day.origin();
        if (previous.isPresent() && from == null) {
            return SlotCheck.unknownTravel();
        }
        if (from != null) {
            OptionalInt minutes = travel.minutes(from, destination);
            if (minutes.isEmpty()) {
                return SlotCheck.unknownTravel();
            }
            travelBefore = minutes.getAsInt();
            Instant free = previous.map(visit -> visit.endAt().plus(Duration.ofMinutes(day.bufferMinutes())))
                    .orElse(day.at(day.open()));
            if (free.plus(Duration.ofMinutes(travelBefore)).isAfter(start)) {
                return SlotCheck.unavailable(SlotUnavailableReason.TRAVEL_CONFLICT);
            }
        }
        if (next.isPresent()) {
            GeoPoint to = next.get().location();
            OptionalInt minutes = to == null ? OptionalInt.empty() : travel.minutes(destination, to);
            if (minutes.isEmpty()) {
                return SlotCheck.unknownTravel();
            }
            Instant arrival = end.plus(Duration.ofMinutes(day.bufferMinutes() + minutes.getAsInt()));
            if (arrival.isAfter(next.get().startAt())) {
                return SlotCheck.unavailable(SlotUnavailableReason.TRAVEL_CONFLICT);
            }
        }
        return SlotCheck.available(travelBefore);
    }
}
