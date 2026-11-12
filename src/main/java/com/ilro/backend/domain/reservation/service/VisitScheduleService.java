package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.reservation.service.VisitTimeline.DayPlan;
import com.ilro.backend.global.common.util.KstTime;
import com.ilro.backend.infra.map.GeoPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

// 이동시간 반영 일정 계산 (BE-10) - 고객 후보(C-12)·수동 예약(B-18)·예약 변경(B-19)·사장님 일정(B-17·B-28)이 같은 계산을 씀
// 시간 검사는 SlotChecker 하나 (명세 5.2 3번), 이동시간은 MapLookup (요청을 넘는 기억, 지도 오류는 '모름')
// 고객 후보 조회(AvailableSlotService)는 여러 날을 한 번에 읽으려고 같은 부품을 직접 씀
@Service
@RequiredArgsConstructor
public class VisitScheduleService {

    private final OwnerScheduleReader scheduleReader;
    private final MapLookup mapLookup;
    private final Clock clock;

    // 한 시각 검사 - 예약 확정(BE-11)·수동 예약(B-18)·변경 제안·수락(B-19)에서 부름
    // destination: 방문 주소 좌표 (모르면 null - 앞뒤 이동을 봐야 하면 travelUnknown)
    // excludeReservationId: 바꾸려는 예약 (자기 예약은 겹침·하루 한도·앞뒤 방문에서 뺌, 명세 5.3), 새 예약이면 null
    // 업체 잠금(명세 1.7)은 부르는 쪽 - 저장 직전에 잠근 뒤 다시 부름
    // travelUnknown이면 시간을 임의로 만들지 않음: 고객 자동 예약은 사장님 확인, 사장님 화면은 '확인 필요' 표시
    public SlotCheck check(Owner owner, Instant startAt, int workMinutes, GeoPoint destination,
                           Long excludeReservationId) {
        LocalDate date = startAt.atZone(KstTime.ZONE).toLocalDate();
        ScheduleDay day = scheduleReader.read(owner, date, date.plusDays(1)).get(date);
        if (excludeReservationId != null) {
            day = day.withoutVisit(excludeReservationId);
        }
        return SlotChecker.check(day, startAt, workMinutes, destination, clock.instant(), mapLookup.newRequest());
    }

    // 기간(한국 날짜 [from, to))의 날짜별 일정 - 방문마다 이동·여유·작업시간을 나눠서 (B-28 표시, 조회 API는 BE-12)
    @Transactional(readOnly = true)
    public List<DayPlan> plan(Owner owner, LocalDate from, LocalDate to) {
        TravelTimes travel = mapLookup.newRequest();
        return scheduleReader.read(owner, from, to).values().stream()
                .map(day -> VisitTimeline.plan(day, travel))
                .toList();
    }
}
