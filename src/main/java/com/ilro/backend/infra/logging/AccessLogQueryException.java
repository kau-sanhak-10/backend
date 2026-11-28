package com.ilro.backend.infra.logging;

// 접근 기록을 읽지 못함 (CloudWatch 권한·통신 등) → 502 ACCESS_LOG_QUERY_FAILED (명세 3.7)
public class AccessLogQueryException extends RuntimeException {

    public AccessLogQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
