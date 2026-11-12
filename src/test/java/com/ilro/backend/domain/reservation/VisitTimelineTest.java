package com.ilro.backend.domain.reservation;

import com.ilro.backend.domain.reservation.service.ScheduleDay;
import com.ilro.backend.domain.reservation.service.ScheduleDay.Visit;
import com.ilro.backend.domain.reservation.service.TravelTimes;
import com.ilro.backend.domain.reservation.service.VisitTimeline;
import com.ilro.backend.domain.reservation.service.VisitTimeline.DayPlan;
import com.ilro.backend.domain.reservation.service.VisitTimeline.PlannedVisit;
import com.ilro.backend.domain.reservation.service.VisitTimeline.TravelStatus;
import com.ilro.backend.infra.map.GeoPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

// 하루 일정 계산 (BE-10, B-28) - 방문마다 이동·여유·작업시간을 나누고, 빠듯하거나 모르면 사장님 확인 필요
class VisitTimelineTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate DATE = LocalDate.of(2099, 1, 5);
    private static final GeoPoint ORIGIN = point("37.600", "126.800");
    private static final GeoPoint P1 = point("37.634", "126.832");
    private static final GeoPoint P2 = point("37.700", "126.900");
    private static final GeoPoint P3 = point("37.650", "126.850");

    // 구간별 이동시간 (분), 없으면 20분 - 빈 값으로 둔 구간은 지도 실패
    private final Map<List<GeoPoint>, OptionalInt> minutes = new HashMap<>();
    private final List<List<GeoPoint>> asked = new ArrayList<>();

    @Test
    @DisplayName("첫 방문은 출발지 이동시간만, 그다음부터 앞 방문 끝 + 여유시간 + 이동시간과 시작 사이 남는 분")
    void breakdown() {
        minutes.put(List.of(ORIGIN, P1), OptionalInt.of(25));
        minutes.put(List.of(P1, P2), OptionalInt.of(10));
        // 09:30~10:30 P1 → 11:00~12:30 P2 (10:30 + 여유 15 + 이동 10 = 10:55 도착, 5분 남음)
        DayPlan plan = plan(day(ORIGIN, visit(2, 11, 0, 12, 30, P2), visit(1, 9, 30, 10, 30, P1)));

        assertThat(plan.date()).isEqualTo(DATE);
        assertThat(plan.visits())
                .extracting(PlannedVisit::reservationId, PlannedVisit::workMinutes, PlannedVisit::previousReservationId,
                        PlannedVisit::travelMinutes, PlannedVisit::bufferMinutes, PlannedVisit::slackMinutes,
                        PlannedVisit::travelStatus)
                .containsExactly(
                        tuple(1L, 60, null, 25, null, null, TravelStatus.OK),
                        tuple(2L, 90, 1L, 10, 15, 5, TravelStatus.OK));
        assertThat(plan.needsCheck()).isFalse();
    }

    @Test
    @DisplayName("여유시간은 방문 사이마다 한 번만 - 여유 15 + 이동 10이면 25분 간격이 딱 맞고, 1분 모자라면 충돌")
    void bufferCountedOnce() {
        minutes.put(List.of(P1, P2), OptionalInt.of(10));

        PlannedVisit exact = plan(day(ORIGIN, visit(1, 9, 30, 10, 30, P1), visit(2, 10, 55, 11, 30, P2)))
                .visits().get(1);
        assertThat(exact.slackMinutes()).isZero();
        assertThat(exact.travelStatus()).isEqualTo(TravelStatus.OK);

        PlannedVisit short1 = plan(day(ORIGIN, visit(1, 9, 30, 10, 30, P1), visit(2, 10, 54, 11, 30, P2)))
                .visits().get(1);
        assertThat(short1.slackMinutes()).isEqualTo(-1);
        assertThat(short1.travelStatus()).isEqualTo(TravelStatus.CONFLICT);
        assertThat(short1.needsCheck()).isTrue();
    }

    @Test
    @DisplayName("지도 실패·좌표 없음은 이동시간을 만들지 않고 UNKNOWN, 여유시간만으로도 모자라면 CONFLICT")
    void unknownTravel() {
        minutes.put(List.of(P1, P2), OptionalInt.empty());
        // P1 → P2 지도 실패 (여유 15분은 됨) / P2 → 좌표 없는 방문: 12:00 끝 + 15 > 12:10 시작
        DayPlan plan = plan(day(ORIGIN, visit(1, 9, 30, 10, 30, P1), visit(2, 11, 0, 12, 0, P2),
                visit(3, 12, 10, 13, 0, null)));

        assertThat(plan.visits())
                .extracting(PlannedVisit::reservationId, PlannedVisit::travelMinutes, PlannedVisit::bufferMinutes,
                        PlannedVisit::slackMinutes, PlannedVisit::travelStatus)
                .containsExactly(
                        tuple(1L, 20, null, null, TravelStatus.OK),
                        tuple(2L, null, 15, null, TravelStatus.UNKNOWN),
                        tuple(3L, null, 15, -5, TravelStatus.CONFLICT));
        assertThat(plan.needsCheck()).isTrue();
        assertThat(asked).as("좌표 없는 방문으로 가는 길은 지도에 묻지 않음")
                .containsExactly(List.of(ORIGIN, P1), List.of(P1, P2));
    }

    @Test
    @DisplayName("첫 방문 - 출발지가 없으면 NO_ORIGIN(확인 필요 아님), 출발지에서 지도가 실패하면 UNKNOWN")
    void firstVisit() {
        PlannedVisit noOrigin = plan(day(null, visit(1, 10, 0, 11, 0, P1))).visits().getFirst();
        assertThat(noOrigin.travelStatus()).isEqualTo(TravelStatus.NO_ORIGIN);
        assertThat(noOrigin.needsCheck()).isFalse();
        assertThat(asked).isEmpty();

        minutes.put(List.of(ORIGIN, P1), OptionalInt.empty());
        PlannedVisit mapFailed = plan(day(ORIGIN, visit(1, 10, 0, 11, 0, P1))).visits().getFirst();
        assertThat(mapFailed.travelStatus()).isEqualTo(TravelStatus.UNKNOWN);
        assertThat(mapFailed.travelMinutes()).isNull();
    }

    @Test
    @DisplayName("긴 방문 안에 겹친 방문은 충돌, 그 뒤 방문은 가장 늦게 끝나는 앞 방문 기준, 전날 시작한 방문은 넣지 않음")
    void overlappingVisits() {
        Visit fromYesterday = new Visit(9L, DATE.minusDays(1).atTime(23, 0).atZone(KST).toInstant(),
                at(0, 30), P3);
        // 09:00~13:00 P1 안에 10:00~11:00 P2, 그 뒤 13:40 P3 (13:00 + 15 + 20 = 13:35, 5분 남음)
        DayPlan plan = plan(day(ORIGIN, fromYesterday, visit(1, 9, 0, 13, 0, P1), visit(2, 10, 0, 11, 0, P2),
                visit(3, 13, 40, 14, 30, P3)));

        assertThat(plan.visits())
                .extracting(PlannedVisit::reservationId, PlannedVisit::previousReservationId,
                        PlannedVisit::slackMinutes, PlannedVisit::travelStatus)
                .containsExactly(
                        tuple(1L, null, null, TravelStatus.OK),
                        tuple(2L, 1L, -215, TravelStatus.CONFLICT),
                        tuple(3L, 1L, 5, TravelStatus.OK));
    }

    private DayPlan plan(ScheduleDay day) {
        return VisitTimeline.plan(day, new TravelTimes((from, to) -> {
            asked.add(List.of(from, to));
            return minutes.getOrDefault(List.of(from, to), OptionalInt.of(20));
        }));
    }

    // 09:00~18:00, 점심 없음, 여유시간 15분
    private static ScheduleDay day(GeoPoint origin, Visit... visits) {
        return new ScheduleDay(DATE, LocalTime.of(9, 0), LocalTime.of(18, 0), null, null, null, 15, origin,
                List.of(), List.of(visits));
    }

    private static Visit visit(long id, int startHour, int startMinute, int endHour, int endMinute,
                               GeoPoint location) {
        return new Visit(id, at(startHour, startMinute), at(endHour, endMinute), location);
    }

    private static Instant at(int hour, int minute) {
        return DATE.atTime(hour, minute).atZone(KST).toInstant();
    }

    private static GeoPoint point(String lat, String lng) {
        return new GeoPoint(new BigDecimal(lat), new BigDecimal(lng));
    }
}
