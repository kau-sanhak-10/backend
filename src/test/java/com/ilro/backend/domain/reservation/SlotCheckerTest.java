package com.ilro.backend.domain.reservation;

import com.ilro.backend.domain.reservation.exception.SlotUnavailableReason;
import com.ilro.backend.domain.reservation.service.ScheduleDay;
import com.ilro.backend.domain.reservation.service.ScheduleDay.TimeRange;
import com.ilro.backend.domain.reservation.service.ScheduleDay.Visit;
import com.ilro.backend.domain.reservation.service.SlotCheck;
import com.ilro.backend.domain.reservation.service.SlotChecker;
import com.ilro.backend.domain.reservation.service.TravelTimes;
import com.ilro.backend.infra.map.GeoPoint;
import com.ilro.backend.infra.map.MapClient;
import com.ilro.backend.infra.map.ServiceAreaMatch;
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

// 방문 시각 검사기 (명세 5.2 3번, BE-09) - 영업시간·점심·차단·하루 한도·겹침·앞뒤 이동시간 + 여유시간
class SlotCheckerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate DATE = LocalDate.of(2099, 1, 5);
    private static final Instant NOW = Instant.parse("2099-01-01T00:00:00Z");
    private static final GeoPoint ORIGIN = point("37.600", "126.800");
    private static final GeoPoint CUSTOMER = point("37.634", "126.832");
    private static final GeoPoint OTHER = point("37.700", "126.900");

    // 구간별 이동시간 (분), 없으면 20분 - 빈 값으로 둔 구간은 지도 실패
    private final Map<List<GeoPoint>, OptionalInt> minutes = new HashMap<>();
    private final List<List<GeoPoint>> asked = new ArrayList<>();
    private final MapClient map = new MapClient() {
        @Override
        public OptionalInt carTravelMinutes(GeoPoint from, GeoPoint to) {
            asked.add(List.of(from, to));
            return minutes.getOrDefault(List.of(from, to), OptionalInt.of(20));
        }

        @Override
        public ServiceAreaMatch matchServiceArea(GeoPoint point, List<String> regionCodes) {
            return ServiceAreaMatch.INSIDE;
        }
    };

    @Test
    @DisplayName("지난 시각·영업시간 밖·점심·차단·겹침 - 처음 걸린 이유 하나")
    void timeRules() {
        ScheduleDay day = day(null, ORIGIN, List.of(range(15, 0, 16, 0)), List.of(visit(10, 0, 11, 0, OTHER)));

        assertThat(check(day, 14, 0, Instant.parse("2099-01-06T00:00:00Z")).reason())
                .isEqualTo(SlotUnavailableReason.PAST);
        assertThat(check(day, 8, 30).reason()).isEqualTo(SlotUnavailableReason.OUTSIDE_BUSINESS_HOURS);
        assertThat(check(day, 17, 30).reason()).as("끝이 영업 종료 뒤")
                .isEqualTo(SlotUnavailableReason.OUTSIDE_BUSINESS_HOURS);
        assertThat(check(day, 11, 30).reason()).isEqualTo(SlotUnavailableReason.LUNCH_BREAK);
        assertThat(check(day, 15, 30).reason()).isEqualTo(SlotUnavailableReason.BLOCKED);
        assertThat(check(day, 10, 30).reason()).isEqualTo(SlotUnavailableReason.OVERLAP);
        assertThat(check(day, 16, 0).isAvailable()).as("차단 끝 시각에 바로 시작").isTrue();
        assertThat(check(day, 17, 0).isAvailable()).as("영업 종료 시각에 끝남").isTrue();
    }

    @Test
    @DisplayName("점심을 안 정했으면 점심 검사 없음, 휴무일은 영업시간 밖")
    void noLunchAndClosedDay() {
        ScheduleDay noLunch = new ScheduleDay(DATE, LocalTime.of(9, 0), LocalTime.of(18, 0), null, null, null, 15,
                ORIGIN, List.of(), List.of());
        assertThat(check(noLunch, 12, 0).isAvailable()).isTrue();

        ScheduleDay closed = new ScheduleDay(DATE, null, null, null, null, null, 15, ORIGIN, List.of(), List.of());
        assertThat(check(closed, 10, 0).reason()).isEqualTo(SlotUnavailableReason.OUTSIDE_BUSINESS_HOURS);
    }

    @Test
    @DisplayName("하루 방문 한도 - 그날 시작하는 취소 안 된 예약 수가 한도면 빈 시간도 불가")
    void dailyLimit() {
        ScheduleDay day = day(2, ORIGIN, List.of(),
                List.of(visit(9, 0, 10, 0, OTHER), visit(16, 0, 17, 0, OTHER)));

        assertThat(check(day, 13, 0).reason()).isEqualTo(SlotUnavailableReason.DAILY_LIMIT_REACHED);
        assertThat(check(day(3, ORIGIN, List.of(), day.visits()), 13, 0).isAvailable()).isTrue();
    }

    @Test
    @DisplayName("그날 첫 방문 - 영업 시작 시각에 출발지에서 떠나 이동시간 안에 도착해야 함, 이동시간은 결과에 남김")
    void firstVisitFromOrigin() {
        minutes.put(List.of(ORIGIN, CUSTOMER), OptionalInt.of(30));
        ScheduleDay day = day(null, ORIGIN, List.of(), List.of());

        assertThat(check(day, 9, 0).reason()).isEqualTo(SlotUnavailableReason.TRAVEL_CONFLICT);
        SlotCheck ok = check(day, 9, 30);
        assertThat(ok.isAvailable()).isTrue();
        assertThat(ok.travelMinutes()).isEqualTo(30);
    }

    @Test
    @DisplayName("앞 방문 - 끝난 뒤 여유시간 15분 + 이동시간 20분, 출발지가 아니라 앞 방문지에서 계산")
    void afterPreviousVisit() {
        minutes.put(List.of(ORIGIN, CUSTOMER), OptionalInt.of(90));
        ScheduleDay day = day(null, ORIGIN, List.of(), List.of(visit(13, 0, 14, 0, OTHER)));

        assertThat(check(day, 14, 30).reason()).as("14:00 + 15 + 20 = 14:35")
                .isEqualTo(SlotUnavailableReason.TRAVEL_CONFLICT);
        SlotCheck ok = check(day, 15, 0);
        assertThat(ok.isAvailable()).isTrue();
        assertThat(ok.travelMinutes()).isEqualTo(20);
        assertThat(asked).contains(List.of(OTHER, CUSTOMER));
    }

    @Test
    @DisplayName("다음 방문 - 끝난 뒤 여유시간 + 이동시간 안에 다음 방문지에 가야 함")
    void beforeNextVisit() {
        minutes.put(List.of(ORIGIN, CUSTOMER), OptionalInt.of(0));
        minutes.put(List.of(CUSTOMER, OTHER), OptionalInt.of(25));
        ScheduleDay day = day(null, ORIGIN, List.of(), List.of(visit(16, 0, 17, 0, OTHER)));

        assertThat(check(day, 14, 30).reason()).as("15:30 + 15 + 25 = 16:10")
                .isEqualTo(SlotUnavailableReason.TRAVEL_CONFLICT);
        assertThat(check(day, 14, 0).isAvailable()).as("15:00 + 40 = 15:40").isTrue();
    }

    @Test
    @DisplayName("이동시간을 모르면 가능·불가로 정하지 않음 - 지도 실패, 앞뒤 예약 좌표 없음")
    void unknownTravel() {
        minutes.put(List.of(ORIGIN, CUSTOMER), OptionalInt.empty());
        assertThat(check(day(null, ORIGIN, List.of(), List.of()), 10, 0).travelUnknown()).isTrue();

        minutes.put(List.of(ORIGIN, CUSTOMER), OptionalInt.of(0));
        ScheduleDay noLocation = day(null, ORIGIN, List.of(), List.of(visit(10, 0, 11, 0, null)));
        assertThat(check(noLocation, 14, 0).travelUnknown()).as("앞 예약 좌표 없음").isTrue();
        assertThat(check(noLocation, 9, 0).travelUnknown()).as("다음 예약 좌표 없음").isTrue();
        assertThat(check(noLocation, 9, 0).isAvailable()).isFalse();
    }

    @Test
    @DisplayName("출발지가 없는 업체는 첫 방문 이동을 보지 않음, 같은 구간은 한 번만 물음")
    void noOriginAndCache() {
        ScheduleDay day = day(null, null, List.of(), List.of());
        SlotCheck first = check(day, 9, 0);
        assertThat(first.isAvailable()).isTrue();
        assertThat(first.travelMinutes()).isNull();

        TravelTimes travel = new TravelTimes(map);
        ScheduleDay withOrigin = day(null, ORIGIN, List.of(), List.of());
        SlotChecker.check(withOrigin, at(10, 0), 60, CUSTOMER, NOW, travel);
        SlotChecker.check(withOrigin, at(14, 0), 60, CUSTOMER, NOW, travel);
        assertThat(asked).containsExactly(List.of(ORIGIN, CUSTOMER));
    }

    // 09:00~18:00, 점심 12:00~13:00, 여유시간 15분, 작업 60분
    private static ScheduleDay day(Integer maxDailyVisits, GeoPoint origin, List<TimeRange> blocks,
                                   List<Visit> visits) {
        return new ScheduleDay(DATE, LocalTime.of(9, 0), LocalTime.of(18, 0), LocalTime.of(12, 0),
                LocalTime.of(13, 0), maxDailyVisits, 15, origin, blocks, visits);
    }

    private SlotCheck check(ScheduleDay day, int hour, int minute) {
        return check(day, hour, minute, NOW);
    }

    private SlotCheck check(ScheduleDay day, int hour, int minute, Instant now) {
        return SlotChecker.check(day, at(hour, minute), 60, CUSTOMER, now, new TravelTimes(map));
    }

    private static Instant at(int hour, int minute) {
        return DATE.atTime(hour, minute).atZone(KST).toInstant();
    }

    private static TimeRange range(int startHour, int startMinute, int endHour, int endMinute) {
        return new TimeRange(at(startHour, startMinute), at(endHour, endMinute));
    }

    private static Visit visit(int startHour, int startMinute, int endHour, int endMinute, GeoPoint location) {
        return new Visit(1L, at(startHour, startMinute), at(endHour, endMinute), location);
    }

    private static GeoPoint point(String lat, String lng) {
        return new GeoPoint(new BigDecimal(lat), new BigDecimal(lng));
    }
}
