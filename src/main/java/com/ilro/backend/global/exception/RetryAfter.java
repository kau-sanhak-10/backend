package com.ilro.backend.global.exception;

// 429 응답의 result - GlobalExceptionHandler가 같은 값을 Retry-After 헤더(초)로도 내려 줌
public record RetryAfter(long retryAfterSeconds) {
}
