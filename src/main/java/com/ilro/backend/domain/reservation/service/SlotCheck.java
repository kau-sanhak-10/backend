package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.reservation.exception.SlotUnavailableReason;

// 한 시각의 검사 결과
// 가능: reason null, travelMinutes는 앞 방문지(그날 첫 방문이면 출발지)에서 오는 이동시간 - 예약에 당시 값으로 저장 (BE-11)
// 이동시간을 셀 수 없음(travelUnknown): 시간을 임의로 만들지 않고 TRAVEL_TIME_UNAVAILABLE로 사장님 확인 (명세 3.5)
public record SlotCheck(SlotUnavailableReason reason, boolean travelUnknown, Integer travelMinutes) {

    public static SlotCheck available(Integer travelMinutes) {
        return new SlotCheck(null, false, travelMinutes);
    }

    public static SlotCheck unavailable(SlotUnavailableReason reason) {
        return new SlotCheck(reason, false, null);
    }

    public static SlotCheck unknownTravel() {
        return new SlotCheck(null, true, null);
    }

    public boolean isAvailable() {
        return reason == null && !travelUnknown;
    }
}
