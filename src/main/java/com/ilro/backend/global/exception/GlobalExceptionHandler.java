package com.ilro.backend.global.exception;

import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.exception.InquiryErrorCode;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.exception.ReservationErrorCode;
import com.ilro.backend.global.common.response.ApiResponse;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.exception.ConstraintErrorMapper.ConstraintError;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 우리가 직접 던진 예외 (result가 있으면 같이 내려감, RetryAfter면 Retry-After 헤더도)
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Object>> handleBusinessException(BusinessException e) {
        log.warn("BusinessException: {}", e.getErrorCode().getCode());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.getErrorCode().getStatus());
        if (e.getResult() instanceof RetryAfter retryAfter) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter.retryAfterSeconds()));
        }
        return response.body(ApiResponse.onFailure(e.getErrorCode(), e.getResult()));
    }

    // @Valid 검증 실패
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<List<FieldErrorResponse>>> handleValidation(MethodArgumentNotValidException e) {
        List<FieldErrorResponse> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErrorResponse(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return toResponse(CommonErrorCode.INVALID_INPUT, errors);
    }

    // 경로·쿼리 값 검증 실패 (@Min 등)
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<List<FieldErrorResponse>>> handleMethodValidation(
            HandlerMethodValidationException e) {
        List<FieldErrorResponse> errors = e.getParameterValidationResults().stream()
                .flatMap(GlobalExceptionHandler::toFieldErrors)
                .toList();
        return toResponse(CommonErrorCode.INVALID_INPUT, errors);
    }

    // 형식이 틀린 경로·쿼리 값 (/inquiries/abc)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<List<FieldErrorResponse>>> handleTypeMismatch(
            MethodArgumentTypeMismatchException e) {
        return toResponse(CommonErrorCode.INVALID_INPUT,
                List.of(new FieldErrorResponse(e.getName(), "형식이 올바르지 않습니다")));
    }

    // 필수 쿼리 값 누락
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<List<FieldErrorResponse>>> handleMissingParameter(
            MissingServletRequestParameterException e) {
        return toResponse(CommonErrorCode.INVALID_INPUT,
                List.of(new FieldErrorResponse(e.getParameterName(), "필수 값입니다")));
    }

    // 필수 헤더 누락 (Idempotency-Key 등)
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<List<FieldErrorResponse>>> handleMissingHeader(MissingRequestHeaderException e) {
        return toResponse(CommonErrorCode.INVALID_INPUT,
                List.of(new FieldErrorResponse(e.getHeaderName(), "필수 값입니다")));
    }

    // JSON 형식이 깨진 요청
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return toResponse(CommonErrorCode.INVALID_INPUT);
    }

    // JSON이 아닌 본문
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return toResponse(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE);
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

    // @Version 충돌 - 서비스가 버전을 먼저 비교하고(최신 상태는 서비스가 result로), 이건 그 사이 끼어든 수정의 마지막 안전장치
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(OptimisticLockingFailureException e) {
        String entityName = e instanceof ObjectOptimisticLockingFailureException objectLock
                ? objectLock.getPersistentClassName() : null;
        ErrorCode errorCode = versionConflictCode(entityName);
        log.warn("Optimistic lock conflict: entity={} -> {}", entityName, errorCode.getCode());
        return toResponse(errorCode);
    }

    // DB 제약 위반 - 제약 이름별 응답은 ConstraintErrorMapper
    // 로그에 예외 메시지를 넣지 않음 (DB 오류 원문에 전화번호 등 요청 값이 들어 있음)
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        ConstraintError error = ConstraintErrorMapper.map(e);
        log.atLevel(error.logLevel()).log("DB constraint violation: constraint={}, sqlState={} -> {}",
                error.constraintName(), error.sqlState(), error.errorCode().getCode());
        return toResponse(error.errorCode(), error.result());
    }

    // 업체 잠금(FOR UPDATE) 3초 초과·교착 - 잠시 뒤 다시 시도하면 됨
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handlePessimisticLock(PessimisticLockingFailureException e) {
        log.warn("Pessimistic lock failure: {}", e.getClass().getSimpleName());
        ErrorCode errorCode = CommonErrorCode.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(errorCode.getStatus())
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ApiResponse.onFailure(errorCode));
    }

    // 위에서 못 잡은 나머지 전부
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unhandled exception", e);
        return toResponse(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    private static ErrorCode versionConflictCode(String entityName) {
        if (entityName == null) {
            return CommonErrorCode.DATA_CONFLICT;
        }
        // 지연 로딩 프록시면 이름 뒤에 $HibernateProxy... 가 붙음
        if (entityName.startsWith(Reservation.class.getName())) {
            return ReservationErrorCode.RESERVATION_VERSION_CONFLICT;
        }
        if (entityName.startsWith(Inquiry.class.getName())) {
            return InquiryErrorCode.INQUIRY_VERSION_CONFLICT;
        }
        return CommonErrorCode.DATA_CONFLICT;
    }

    private static Stream<FieldErrorResponse> toFieldErrors(ParameterValidationResult result) {
        // @Valid 객체 파라미터는 칸 이름, 단일 값 파라미터는 파라미터 이름
        if (result instanceof ParameterErrors errors) {
            return errors.getFieldErrors().stream()
                    .map(fe -> new FieldErrorResponse(fe.getField(), fe.getDefaultMessage()));
        }
        String name = parameterName(result.getMethodParameter());
        return result.getResolvableErrors().stream()
                .map(error -> new FieldErrorResponse(name, error.getDefaultMessage()));
    }

    // @RequestParam("page")처럼 이름을 따로 적었으면 그 이름 (클라이언트가 보낸 이름)
    private static String parameterName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null && !requestParam.name().isEmpty()) {
            return requestParam.name();
        }
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        if (pathVariable != null && !pathVariable.name().isEmpty()) {
            return pathVariable.name();
        }
        RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
        if (requestHeader != null && !requestHeader.name().isEmpty()) {
            return requestHeader.name();
        }
        return parameter.getParameterName();
    }

    private ResponseEntity<ApiResponse<Void>> toResponse(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.onFailure(errorCode));
    }

    private <T> ResponseEntity<ApiResponse<T>> toResponse(ErrorCode errorCode, T result) {
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.onFailure(errorCode, result));
    }
}
