package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.reservation.service.ScheduleDay.Visit;
import com.ilro.backend.domain.reservation.service.VisitTimeline.DayPlan;
import com.ilro.backend.global.common.util.KstTime;
import com.ilro.backend.infra.map.GeoPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// 이동시간 반영 일정 계산 (BE-10) - 고객 후보(C-12)·수동 예약(B-18)·예약 변경(B-19)·사장님 일정(B-17·B-28)이 같은 계산을 씀
// 시간 검사는 SlotChecker 하나 (명세 5.2 3번), 이동시간은 MapLookup (요청을 넘는 기억, 셀 수 없다는 답과 지도 장애를 나눔)
// 고객 후보 조회(AvailableSlotService)는 여러 날을 한 번에 읽으려고 같은 부품을 직접 씀
@Service
@RequiredArgsConstructor
public class VisitScheduleService {

    // 자정을 사이에 둔 두 방문을 이어진 동선으로 보는 간격 - 이보다 가까우면 전날 마지막·다음 날 첫 방문도 앞뒤 방문으로 봄
    // 하루는 영업 시작 시각에 출발지에서 시작한다고 보지만(SlotChecker), 영업시간 밖 수동 예약(23:00, 00:15)은
    // 밤사이에 붙을 수 있어 그 사이의 이동시간·여유시간을 봐야 함. 보통 영업시간(예: 09~18시)끼리는 이 간격보다 멀어 영향 없음
    static final Duration NIGHT_GAP = Duration.ofHours(6);

    private final OwnerScheduleReader scheduleReader;
    private final MapLookup mapLookup;
    private final Clock clock;

    // 한 시각 검사 - 예약 확정(BE-11)·수동 예약(B-18)·변경 제안·수락(B-19)에서 부름
    // destination: 방문 주소 좌표 (모르면 null - 앞뒤 이동을 봐야 하면 travelUnknown)
    // excludeReservationId: 바꾸려는 예약 (자기 예약은 겹침·하루 한도·앞뒤 방문에서 뺌, 명세 5.3), 새 예약이면 null
    // 업체 잠금(명세 1.7)은 부르는 쪽 - 저장 직전에 잠근 뒤 다시 부름
    // travelUnknown이면 시간을 임의로 만들지 않음: 고객 자동 예약은 사장님 확인, 사장님 화면은 '확인 필요' 표시
    // 지도 장애면 MapUnavailableException (503 '잠시 후 다시') - 문의·예약 상태를 바꾸지 않고 끝냄
    public SlotCheck check(Owner owner, Instant startAt, int workMinutes, GeoPoint destination,
                           Long excludeReservationId) {
        return check(owner, startAt, workMinutes, destination, excludeReservationId, mapLookup.newRequest());
    }

    // 이동시간 묻는 곳을 정해서 검사 - 예약 확정(BE-11)은 잠그기 전 검사에서 물은 구간을 잠근 뒤 다시 씀 (TravelTimes.askedOnly)
    public SlotCheck check(Owner owner, Instant startAt, int workMinutes, GeoPoint destination,
                           Long excludeReservationId, TravelTimes travel) {
        return check(owner, startAt, workMinutes, destination, excludeReservationId, travel, false);
    }

    // ignoreBusinessHours: 사장님이 영업시간 밖 경고를 확인한 수동 예약 (BE-12) - 영업시간만 빼고 같은 검사
    public SlotCheck check(Owner owner, Instant startAt, int workMinutes, GeoPoint destination,
                           Long excludeReservationId, TravelTimes travel, boolean ignoreBusinessHours) {
        LocalDate date = startAt.atZone(KstTime.ZONE).toLocalDate();
        Instant endAt = startAt.plus(Duration.ofMinutes(workMinutes));
        // 자정 가까이에서 시작하거나 끝나는 방문이면 전날·다음 날 일정도 같이 읽음 (그 밖에는 그날만)
        boolean nearPreviousDay = startAt.isBefore(date.atStartOfDay(KstTime.ZONE).toInstant().plus(NIGHT_GAP));
        boolean nearNextDay = endAt.isAfter(date.plusDays(1).atStartOfDay(KstTime.ZONE).toInstant().minus(NIGHT_GAP));
        Map<LocalDate, ScheduleDay> days = scheduleReader.read(owner, nearPreviousDay ? date.minusDays(1) : date,
                nearNextDay ? date.plusDays(2) : date.plusDays(1));
        ScheduleDay day = days.get(date);
        if (excludeReservationId != null) {
            day = day.withoutVisit(excludeReservationId);
        }
        if (ignoreBusinessHours) {
            day = day.withAllDayHours();
        }
        day = day.withNeighbors(nightNeighbors(days, date, startAt, endAt, excludeReservationId));
        return SlotChecker.check(day, startAt, workMinutes, destination, clock.instant(), travel);
    }

    // 자정 너머의 가까운 앞뒤 방문 - 전날 방문 중 이 방문 전에 끝나는 가장 늦은 것, 다음 날 방문 중 이 방문 뒤에 시작하는
    // 가장 이른 것. 간격이 NIGHT_GAP보다 가까울 때만 (바꾸려는 자기 예약은 뺌)
    private static List<Visit> nightNeighbors(Map<LocalDate, ScheduleDay> days, LocalDate date, Instant startAt,
                                              Instant endAt, Long excludeReservationId) {
        List<Visit> neighbors = new ArrayList<>();
        ScheduleDay previousDay = days.get(date.minusDays(1));
        if (previousDay != null) {
            previousDay.visits().stream()
                    .filter(visit -> !Objects.equals(visit.reservationId(), excludeReservationId))
                    .filter(visit -> !visit.endAt().isAfter(startAt)
                            && Duration.between(visit.endAt(), startAt).compareTo(NIGHT_GAP) < 0)
                    .max(Comparator.comparing(Visit::endAt))
                    .ifPresent(neighbors::add);
        }
        ScheduleDay nextDay = days.get(date.plusDays(1));
        if (nextDay != null) {
            nextDay.visits().stream()
                    .filter(visit -> !Objects.equals(visit.reservationId(), excludeReservationId))
                    .filter(visit -> !visit.startAt().isBefore(endAt)
                            && Duration.between(endAt, visit.startAt()).compareTo(NIGHT_GAP) < 0)
                    .min(Comparator.comparing(Visit::startAt))
                    .ifPresent(neighbors::add);
        }
        return neighbors;
    }

    // 한 요청에서 쓸 이동시간 - 같은 구간은 한 번만 지도에 물음
    public TravelTimes newTravelRequest() {
        return mapLookup.newRequest();
    }

    // 기간(한국 날짜 [from, to))의 날짜별 일정 - 방문마다 이동·여유·작업시간을 나눠서 (B-28 표시, 조회 API는 BE-12)
    // 보여 주기만 하므로 지도 장애여도 일정은 보여 주고 그 이동시간만 UNKNOWN (다음 조회 때 다시 셈)
    @Transactional(readOnly = true)
    public List<DayPlan> plan(Owner owner, LocalDate from, LocalDate to) {
        TravelTimes travel = mapLookup.newDisplayRequest();
        return scheduleReader.read(owner, from, to).values().stream()
                .map(day -> VisitTimeline.plan(day, travel))
                .toList();
    }
}
