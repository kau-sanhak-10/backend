package com.ilro.backend.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "잘못된 요청입니다"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "접속 시간이 지났어요. 다시 인증해 주세요"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "이 기능을 사용할 권한이 없습니다"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 리소스를 찾을 수 없습니다"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "지원하지 않는 HTTP 메서드입니다"),
    // 같은 Idempotency-Key로 내용·요청자가 다른 요청 (명세 1.6)
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "같은 요청 번호로 다른 내용이 들어왔습니다"),
    // 대응표(ConstraintErrorMapper)에 없는 DB 제약 위반, 대상을 모르는 버전 충돌
    DATA_CONFLICT(HttpStatus.CONFLICT, "DATA_CONFLICT", "다른 요청과 겹쳤어요. 다시 시도해 주세요"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 요청 형식입니다"),
    // result: retryAfterSeconds
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "요청이 너무 많아요. 잠시 후 다시 시도해 주세요"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다"),
    // result: service (SMS·MAP·AI·PUSH·CLOUDWATCH)
    EXTERNAL_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "EXTERNAL_SERVICE_ERROR", "외부 서비스 연결에 실패했어요"),
    // 업체 잠금 대기 초과 등, 응답에 Retry-After 헤더
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "잠시 사용할 수 없어요");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
