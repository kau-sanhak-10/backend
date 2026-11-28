package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.common.util.KstTime;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

// 고객이 고를 수 있는 시작 시각의 틀 - 후보 조회(BE-09)가 내놓는 시각과 같은 기준으로 예약 확정(BE-11)을 검사
//   후보는 그날 영업 시작부터 slot-step 간격 (AvailableSlotService) → 확정도 그 간격 위의 시각만 받음
//   사장님 수동 예약·변경 제안은 사장님이 정하는 시각이라 이 틀을 쓰지 않음
@Component
public class SlotGrid {

    // 받을 수 있는 시각의 범위 - 예약 기간 정책이 아니라, 달력 계산이 넘치는 터무니없는 날짜를 400으로 돌려보내는 안전선
    static final Duration MAX_DISTANCE = Duration.ofDays(366);

    private final OwnerScheduleReader scheduleReader;
    private final Clock clock;
    private final Duration step;

    public SlotGrid(OwnerScheduleReader scheduleReader, Clock clock,
                    @Value("${app.booking.slot-step}") Duration step) {
        this.scheduleReader = scheduleReader;
        this.clock = clock;
        this.step = step;
    }

    // 지금에서 1년 넘게 떨어진 시각은 일정을 읽기 전에 400 (지난 시각은 SLOT_UNAVAILABLE(PAST)로 그대로)
    public void requireReasonable(Instant startAt) {
        Instant now = clock.instant();
        if (startAt.isBefore(now.minus(MAX_DISTANCE)) || startAt.isAfter(now.plus(MAX_DISTANCE))) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    List.of(new FieldErrorResponse("startAt", "예약할 수 있는 날짜가 아닙니다")));
        }
    }

    // 그날 영업 시작에서 slot-step의 배수만큼 떨어진 시각인지
    // 휴무일은 틀이 없어 true - 영업시간 검사(OUTSIDE_BUSINESS_HOURS)가 막음
    public boolean offers(Owner owner, Instant startAt) {
        LocalDate date = startAt.atZone(KstTime.ZONE).toLocalDate();
        ScheduleDay day = scheduleReader.read(owner, date, date.plusDays(1)).get(date);
        if (day == null || day.isClosed()) {
            return true;
        }
        return Duration.between(day.at(day.open()), startAt).toMillis() % step.toMillis() == 0;
    }
}
