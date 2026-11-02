package com.ilro.backend.domain.owner.exception;

import com.ilro.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// 업체 설정·운영 오류 코드 (명세 3.3·3.7) - 지금은 DB 제약 대응(3.10)에 쓰는 것만, 나머지는 BE-03·BE-17에서 추가
@Getter
@RequiredArgsConstructor
public enum OwnerErrorCode implements ErrorCode {

    OWNER_PHONE_DUPLICATED(HttpStatus.CONFLICT, "OWNER_PHONE_DUPLICATED", "같은 로그인 번호로 등록된 업체가 있습니다"),
    WORK_TYPE_DUPLICATED(HttpStatus.CONFLICT, "WORK_TYPE_DUPLICATED", "이미 등록된 작업 종류입니다"),
    SERVICE_AREA_DUPLICATED(HttpStatus.CONFLICT, "SERVICE_AREA_DUPLICATED", "이미 등록된 서비스 지역입니다");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
