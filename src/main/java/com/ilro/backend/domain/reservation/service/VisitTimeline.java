package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.reservation.service.ScheduleDay.Visit;
import com.ilro.backend.global.common.util.KstTime;
import com.ilro.backend.infra.map.GeoPoint;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;

// 하루 일정 계산 (BE-10, B-28) - 방문마다 앞 방문지(그날 첫 방문은 업체 출발지)에서 오는 이동시간,
// 앞 방문과 사이의 여유시간, 작업시간을 나눠서 셈 (사장님 일정 조회 B-17이 그대로 보여 줌)
// 이동시간과 여유시간은 방문 사이마다 한 번씩만 - 여유시간을 이동시간으로 대신하거나 두 번 더하지 않음 (B-08·B-28)
// 이동시간은 지금 앞뒤 방문으로 다시 셈 - 예약에 저장한 travel_time_minutes는 그 뒤 일정이 바뀌면 달라질 수 있음
public final class VisitTimeline {

    private VisitTimeline() {
    }

    public enum TravelStatus {
        // 앞 방문이 끝나고 여유시간 + 이동시간 뒤에 도착할 수 있음 (첫 방문은 출발지 이동시간을 셈)
        OK,
        // 여유시간 + 이동시간이 모자람 - slackMinutes가 모자란 분 (음수)
        CONFLICT,
        // 좌표가 없거나 지도가 답하지 못해 이동시간을 모름 - 시간을 임의로 만들지 않고 사장님 확인 필요
        UNKNOWN,
        // 그날 첫 방문인데 업체 출발지가 없어 이동시간을 셀 곳이 없음
        NO_ORIGIN
    }

    // previousReservationId: 앞 방문 (그날 첫 방문이면 null - 출발지에서 옴)
    // workMinutes: 시작~끝 (예약에 잡힌 작업시간)
    // travelMinutes: 앞 방문지·출발지에서 오는 자동차 이동시간 (모르거나 셀 곳이 없으면 null)
    // bufferMinutes: 앞 방문과 사이에 두는 여유시간 (첫 방문은 null - 출발 시각은 사장님이 정함)
    // slackMinutes: 시작 - (앞 방문 끝 + 여유시간 + 이동시간), 음수면 모자람
    //   (첫 방문·이동시간 모름이면 null, 이동시간을 몰라도 여유시간만으로 모자라면 그 값)
    public record PlannedVisit(Long reservationId, Instant startAt, Instant endAt, int workMinutes,
                               Long previousReservationId, Integer travelMinutes, Integer bufferMinutes,
                               Integer slackMinutes, TravelStatus travelStatus) {

        // 사장님이 확인해야 하는 방문 - 이동이 빠듯하거나 이동시간을 모름
        public boolean needsCheck() {
            return travelStatus == TravelStatus.CONFLICT || travelStatus == TravelStatus.UNKNOWN;
        }
    }

    public record DayPlan(LocalDate date, List<PlannedVisit> visits) {

        public boolean needsCheck() {
            return visits.stream().anyMatch(PlannedVisit::needsCheck);
        }
    }

    // 그날(한국 날짜) 시작하는 방문을 시작 시각 순으로
    // 앞 방문 = 먼저 시작한 방문 중 가장 늦게 끝나는 것 (긴 방문 안에 짧은 방문이 겹쳐도 충돌로 잡힘)
    public static DayPlan plan(ScheduleDay day, TravelTimes travel) {
        List<Visit> visits = day.visits().stream()
                .filter(visit -> visit.startAt().atZone(KstTime.ZONE).toLocalDate().equals(day.date()))
                .sorted(Comparator.comparing(Visit::startAt).thenComparing(Visit::endAt))
                .toList();
        List<PlannedVisit> planned = new ArrayList<>();
        Visit previous = null;
        for (Visit visit : visits) {
            planned.add(previous == null ? first(day, visit, travel) : after(day, previous, visit, travel));
            if (previous == null || visit.endAt().isAfter(previous.endAt())) {
                previous = visit;
            }
        }
        return new DayPlan(day.date(), planned);
    }

    // 첫 방문 - 출발지에서 오는 이동시간만 셈, 출발 시각은 사장님이 정하므로 충돌로 보지 않음
    private static PlannedVisit first(ScheduleDay day, Visit visit, TravelTimes travel) {
        if (day.origin() == null) {
            return planned(visit, null, null, null, null, TravelStatus.NO_ORIGIN);
        }
        OptionalInt minutes = minutes(travel, day.origin(), visit.location());
        if (minutes.isEmpty()) {
            return planned(visit, null, null, null, null, TravelStatus.UNKNOWN);
        }
        return planned(visit, null, minutes.getAsInt(), null, null, TravelStatus.OK);
    }

    private static PlannedVisit after(ScheduleDay day, Visit previous, Visit visit, TravelTimes travel) {
        int buffer = day.bufferMinutes();
        OptionalInt minutes = minutes(travel, previous.location(), visit.location());
        if (minutes.isEmpty()) {
            int slack = slack(previous, visit, buffer);
            return slack < 0
                    ? planned(visit, previous.reservationId(), null, buffer, slack, TravelStatus.CONFLICT)
                    : planned(visit, previous.reservationId(), null, buffer, null, TravelStatus.UNKNOWN);
        }
        int slack = slack(previous, visit, buffer + minutes.getAsInt());
        return planned(visit, previous.reservationId(), minutes.getAsInt(), buffer, slack,
                slack < 0 ? TravelStatus.CONFLICT : TravelStatus.OK);
    }

    private static OptionalInt minutes(TravelTimes travel, GeoPoint from, GeoPoint to) {
        return from == null || to == null ? OptionalInt.empty() : travel.minutes(from, to);
    }

    // 앞 방문 끝 + gapMinutes에 도착했을 때 시작까지 남는 분 (모자라면 음수, 1초라도 모자라면 -1분)
    private static int slack(Visit previous, Visit visit, int gapMinutes) {
        Instant arrival = previous.endAt().plus(Duration.ofMinutes(gapMinutes));
        return (int) Math.floorDiv(Duration.between(arrival, visit.startAt()).getSeconds(), 60);
    }

    private static PlannedVisit planned(Visit visit, Long previousId, Integer travelMinutes, Integer bufferMinutes,
                                        Integer slackMinutes, TravelStatus status) {
        int work = (int) Duration.between(visit.startAt(), visit.endAt()).toMinutes();
        return new PlannedVisit(visit.reservationId(), visit.startAt(), visit.endAt(), work, previousId,
                travelMinutes, bufferMinutes, slackMinutes, status);
    }
}
