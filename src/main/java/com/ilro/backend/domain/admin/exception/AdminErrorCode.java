package com.ilro.backend.domain.admin.exception;

import com.ilro.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// 운영 오류 코드 (명세 3.7) - 접근 기록·개인정보 삭제 (O-09, BE-19)
@Getter
@RequiredArgsConstructor
public enum AdminErrorCode implements ErrorCode {

    // result: reasons [{inquiryId, reason}] - 처리 근거가 없거나 미처리 문의·진행 중 예약이 있음. 하나라도 걸리면 아무것도 지우지 않음
    PRIVACY_DELETE_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT, "PRIVACY_DELETE_NOT_ALLOWED",
            "지금은 지울 수 없는 문의가 있습니다"),
    // CloudWatch에서 접근 기록을 읽지 못함 - 0건으로 답하지 않음
    ACCESS_LOG_QUERY_FAILED(HttpStatus.BAD_GATEWAY, "ACCESS_LOG_QUERY_FAILED", "접근 기록을 불러오지 못했습니다");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
