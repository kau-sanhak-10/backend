package com.ilro.backend.global.exception;

import com.ilro.backend.domain.inquiry.exception.InquiryErrorCode;
import com.ilro.backend.domain.owner.exception.OwnerErrorCode;
import com.ilro.backend.domain.reservation.exception.ReservationErrorCode;
import com.ilro.backend.domain.reservation.exception.SlotUnavailableReason;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import org.hibernate.exception.ConstraintViolationException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.event.Level;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;

// DB 제약 위반 → 오류 응답 대응표 (명세 3.10)
// 서버 검사를 통과한 동시 요청이 DB 제약에 걸려도 500으로 나가지 않게 함
// 표의 제약 이름은 마이그레이션의 CONSTRAINT·INDEX 이름 - 이름이 바뀌면 ConstraintErrorMapperSchemaTest가 깨짐
public final class ConstraintErrorMapper {

    private static final String NOT_NULL_VIOLATION = "23502";

    // 응답으로 바로 바꾸는 제약 (409·404·400, 경고 로그)
    private static final Map<String, ConstraintError> RESPONSES = Map.ofEntries(
            entry("ex_reservation_confirmed_overlap", expected(ReservationErrorCode.SLOT_UNAVAILABLE,
                    Map.of("reason", SlotUnavailableReason.OVERLAP))),
            entry("uq_reservation_active_inquiry", expected(ReservationErrorCode.RESERVATION_ALREADY_EXISTS, null)),
            entry("uq_reservation_change_active", expected(ReservationErrorCode.CHANGE_IN_PROGRESS, null)),
            entry("uq_inquiry_field_current", expected(InquiryErrorCode.INQUIRY_VERSION_CONFLICT, null)),
            entry("uq_owner_phone", expected(OwnerErrorCode.OWNER_PHONE_DUPLICATED, null)),
            entry("uq_owner_work_type_owner_category", expected(OwnerErrorCode.WORK_TYPE_DUPLICATED, null)),
            entry("uq_owner_service_area_owner_region", expected(OwnerErrorCode.SERVICE_AREA_DUPLICATED, null)),
            entry("uq_owner_business_hour_owner_day", expected(CommonErrorCode.INVALID_INPUT,
                    List.of(new FieldErrorResponse("businessHours", "같은 요일이 두 번 들어 있습니다")))),
            // 다른 업체의 문의에 예약을 연결하려 함 - 있다는 사실도 숨김
            entry("fk_reservation_inquiry_same_owner", expected(InquiryErrorCode.INQUIRY_NOT_FOUND, null)));

    // 서비스가 새 트랜잭션(REQUIRES_NEW)에서 다시 조회해 처음 결과를 돌려주거나 값을 다시 만드는 제약
    // 여기까지 오면 서비스가 처리를 빠뜨린 것 → 409 DATA_CONFLICT + 오류 로그
    private static final Set<String> HANDLED_BY_SERVICE = Set.of(
            "uq_reservation_idempotency_key", // 지문이 같으면 200 + 처음 결과, 다르면 IDEMPOTENCY_KEY_REUSED
            "uq_message_inquiry_client_msg", // 200 + 처음 저장한 메시지
            "uq_handoff_request_active", // 200 + 진행 중 요청
            "uq_customer_owner_phone", // 기존 고객을 씀
            "uq_owner_link_token", // 토큰을 다시 만들어 한 번 더 저장
            "uq_notification_dedupe_key", // 새로 만들지 않고 넘어감
            "uq_push_subscription_endpoint"); // 갱신으로 처리

    private ConstraintErrorMapper() {
    }

    public static ConstraintError map(DataIntegrityViolationException e) {
        return map(constraintName(e), sqlState(e));
    }

    static ConstraintError map(String constraintName, String sqlState) {
        if (constraintName == null) {
            // NOT NULL 위반은 제약 이름이 없음 - 서버 검증이 먼저 막아야 하므로 버그
            ErrorCode errorCode = NOT_NULL_VIOLATION.equals(sqlState)
                    ? CommonErrorCode.INVALID_INPUT : CommonErrorCode.DATA_CONFLICT;
            return new ConstraintError(errorCode, null, Level.ERROR, null, sqlState);
        }
        ConstraintError response = RESPONSES.get(constraintName);
        if (response != null) {
            return response.withSource(constraintName, sqlState);
        }
        if (HANDLED_BY_SERVICE.contains(constraintName)) {
            return new ConstraintError(CommonErrorCode.DATA_CONFLICT, null, Level.ERROR, constraintName, sqlState);
        }
        if (constraintName.startsWith("fk_")) {
            // fk_*_same_owner·fk_*_same_inquiry(다른 업체·문의 데이터 연결)도 대상별 코드가 없으면 여기
            return new ConstraintError(CommonErrorCode.RESOURCE_NOT_FOUND, null, Level.WARN, constraintName, sqlState);
        }
        if (constraintName.startsWith("ck_")) {
            // 값 범위 위반 - 서버 검증이 먼저 막아야 하므로 버그
            return new ConstraintError(CommonErrorCode.INVALID_INPUT, null, Level.ERROR, constraintName, sqlState);
        }
        // 복합 FK용 UNIQUE, 운영 데이터 입력용 제약(uq_admin_phone 등), 새로 생긴 제약
        return new ConstraintError(CommonErrorCode.DATA_CONFLICT, null, Level.ERROR, constraintName, sqlState);
    }

    // 대응표에 이름을 적은 제약 전부 (스키마에 실제로 있는지 테스트로 확인)
    static Set<String> mappedConstraintNames() {
        Set<String> names = new HashSet<>(RESPONSES.keySet());
        names.addAll(HANDLED_BY_SERVICE);
        return names;
    }

    // PostgreSQL 오류의 제약 이름(언어 설정과 무관)을 먼저 보고, 없으면 Hibernate가 읽은 이름
    // NOT NULL 위반이면 Hibernate는 칸 이름을 돌려주므로 PostgreSQL 쪽을 우선함
    static String constraintName(Throwable e) {
        String hibernateName = null;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PSQLException psql) {
                ServerErrorMessage serverError = psql.getServerErrorMessage();
                return serverError != null ? serverError.getConstraint() : null;
            }
            if (t instanceof ConstraintViolationException violation && hibernateName == null) {
                hibernateName = violation.getConstraintName();
            }
        }
        return hibernateName;
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static ConstraintError expected(ErrorCode errorCode, Object result) {
        return new ConstraintError(errorCode, result, Level.WARN, null, null);
    }

    // 응답 코드·result와 로그 수준, 로그에 남길 제약 이름·SQLState
    // 로그에는 요청 값이 담긴 DB 오류 원문(Key (phone)=(...) 등)을 넣지 않음 (명세 1.8)
    public record ConstraintError(ErrorCode errorCode, Object result, Level logLevel, String constraintName,
                                  String sqlState) {

        private ConstraintError withSource(String constraintName, String sqlState) {
            return new ConstraintError(errorCode, result, logLevel, constraintName, sqlState);
        }
    }
}
