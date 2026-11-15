package com.ilro.backend.domain.address.exception;

import com.ilro.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// 주소·지역 검색 오류 코드 (BE-15)
@Getter
@RequiredArgsConstructor
public enum AddressErrorCode implements ErrorCode {

    // 지도(카카오) 일시 장애 - 예약 쪽(ReservationErrorCode)과 같은 코드, 메시지만 주소 검색에 맞춤
    // 프론트는 코드로 "잠시 후 다시"를 안내하면 됨
    MAP_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "MAP_UNAVAILABLE",
            "지금은 주소를 검색할 수 없어요. 잠시 후 다시 시도해 주세요");

    private final HttpStatus status;
    private final String code;
    private final String message;
}