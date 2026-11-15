package com.ilro.backend.domain.reservation.exception;

import com.ilro.backend.global.exception.BusinessException;

// 지도 일시 장애 - 지도 오류(예외)·시간 초과·약속과 다른 값 (MapLookup이 던짐)
// 503 MAP_UNAVAILABLE로 "잠시 후 다시"만 안내하고 문의는 사장님 확인으로 넘기지 않음 (장애가 끝나면 그대로 이어감)
// 지도가 '셀 수 없다'고 답한 것(빈 값·UNKNOWN)과 다름 - 그건 422 + 사장님 확인
public class MapUnavailableException extends BusinessException {

    public MapUnavailableException() {
        super(ReservationErrorCode.MAP_UNAVAILABLE);
    }
}
