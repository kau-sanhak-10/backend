package com.ilro.backend.domain.inquiry.exception;

import com.ilro.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// 접수·상담 오류 코드 (명세 3.4) - 지금은 DB 제약·버전 충돌 대응(3.10)에 쓰는 것만, 나머지는 BE-05·BE-07에서 추가
@Getter
@RequiredArgsConstructor
public enum InquiryErrorCode implements ErrorCode {

    // 없거나 접근 범위 밖의 문의 - 다른 업체 문의도 있다는 사실을 숨기고 404
    INQUIRY_NOT_FOUND(HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND", "문의를 찾을 수 없습니다"),
    // result: currentVersion
    INQUIRY_VERSION_CONFLICT(HttpStatus.CONFLICT, "INQUIRY_VERSION_CONFLICT", "다른 곳에서 접수 정보가 먼저 바뀌었어요. 최신 내용을 확인해 주세요");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
