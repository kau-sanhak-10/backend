package com.ilro.backend.global.exception;

import com.ilro.backend.global.common.response.ApiResponse;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 우리가 직접 던진 예외
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        log.warn("BusinessException: {}", e.getErrorCode().getCode());
        return toResponse(e.getErrorCode());
    }

    // @Valid 검증 실패
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<List<FieldErrorResponse>>> handleValidation(MethodArgumentNotValidException e) {
        List<FieldErrorResponse> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErrorResponse(fe.getField(), fe.getDefaultMessage()))
                .toList();
        ErrorCode errorCode = CommonErrorCode.INVALID_INPUT;
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.onFailure(errorCode, errors));
    }

    // JSON 형식이 깨진 요청
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return toResponse(CommonErrorCode.INVALID_INPUT);
    }

    // 지원하지 않는 HTTP 메서드 (GET만 있는데 POST로 요청 등)
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return toResponse(CommonErrorCode.METHOD_NOT_ALLOWED);
    }

    // 없는 URL
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        return toResponse(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    // 위에서 못 잡은 나머지 전부
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unhandled exception", e);
        return toResponse(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<ApiResponse<Void>> toResponse(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.onFailure(errorCode));
    }
}