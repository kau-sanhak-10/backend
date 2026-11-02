package com.ilro.backend.global.exception;

import com.ilro.backend.domain.inquiry.exception.InquiryErrorCode;
import com.ilro.backend.domain.owner.exception.OwnerErrorCode;
import com.ilro.backend.domain.reservation.exception.ReservationErrorCode;
import com.ilro.backend.domain.reservation.exception.SlotUnavailableReason;
import com.ilro.backend.global.exception.ConstraintErrorMapper.ConstraintError;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.event.Level;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 제약 이름 → 오류 코드 규칙 (명세 3.10 표), DB 없이 확인
class ConstraintErrorMapperTest {

    @Test
    @DisplayName("대응표에 적은 제약은 정해진 코드로, 경고 로그로 바뀐다")
    void mappedConstraints() {
        assertMapped("ex_reservation_confirmed_overlap", ReservationErrorCode.SLOT_UNAVAILABLE, Level.WARN);
        assertMapped("uq_reservation_active_inquiry", ReservationErrorCode.RESERVATION_ALREADY_EXISTS, Level.WARN);
        assertMapped("uq_reservation_change_active", ReservationErrorCode.CHANGE_IN_PROGRESS, Level.WARN);
        assertMapped("uq_inquiry_field_current", InquiryErrorCode.INQUIRY_VERSION_CONFLICT, Level.WARN);
        assertMapped("uq_owner_phone", OwnerErrorCode.OWNER_PHONE_DUPLICATED, Level.WARN);
        assertMapped("uq_owner_work_type_owner_category", OwnerErrorCode.WORK_TYPE_DUPLICATED, Level.WARN);
        assertMapped("uq_owner_service_area_owner_region", OwnerErrorCode.SERVICE_AREA_DUPLICATED, Level.WARN);
        assertMapped("uq_owner_business_hour_owner_day", CommonErrorCode.INVALID_INPUT, Level.WARN);
        assertMapped("fk_reservation_inquiry_same_owner", InquiryErrorCode.INQUIRY_NOT_FOUND, Level.WARN);

        assertThat(ConstraintErrorMapper.map("ex_reservation_confirmed_overlap", "23P01").result())
                .isEqualTo(Map.of("reason", SlotUnavailableReason.OVERLAP));
    }

    @Test
    @DisplayName("FK 위반은 404, CHECK 위반은 400 + 오류 로그")
    void prefixRules() {
        assertMapped("fk_reservation_customer_same_owner", CommonErrorCode.RESOURCE_NOT_FOUND, Level.WARN);
        assertMapped("fk_photo_message_same_inquiry", CommonErrorCode.RESOURCE_NOT_FOUND, Level.WARN);
        assertMapped("fk_reservation_owner", CommonErrorCode.RESOURCE_NOT_FOUND, Level.WARN);
        assertMapped("ck_reservation_time", CommonErrorCode.INVALID_INPUT, Level.ERROR);
    }

    @Test
    @DisplayName("서비스가 처리해야 하는 제약이나 표에 없는 제약은 409 DATA_CONFLICT + 오류 로그")
    void unhandledConstraints() {
        assertMapped("uq_reservation_idempotency_key", CommonErrorCode.DATA_CONFLICT, Level.ERROR);
        assertMapped("uq_message_inquiry_client_msg", CommonErrorCode.DATA_CONFLICT, Level.ERROR);
        assertMapped("uq_admin_phone", CommonErrorCode.DATA_CONFLICT, Level.ERROR);
        assertMapped("uq_customer_id_owner", CommonErrorCode.DATA_CONFLICT, Level.ERROR);
    }

    @Test
    @DisplayName("제약 이름이 없으면 NOT NULL 위반은 400, 그 밖은 409")
    void missingConstraintName() {
        assertThat(ConstraintErrorMapper.map(null, "23502").errorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT);
        assertThat(ConstraintErrorMapper.map(null, null).errorCode()).isEqualTo(CommonErrorCode.DATA_CONFLICT);
    }

    @Test
    @DisplayName("제약 이름은 PostgreSQL 오류에서 먼저 읽고, 없으면 Hibernate 예외에서 읽는다")
    void readsConstraintNameFromCauses() {
        PSQLException psql = psqlException("23505", "uq_owner_phone");
        DataIntegrityViolationException fromJdbc = new DataIntegrityViolationException("duplicate", psql);
        DataIntegrityViolationException fromHibernate = new DataIntegrityViolationException("duplicate",
                new ConstraintViolationException("duplicate", psql, "sql", "uq_owner_phone"));
        DataIntegrityViolationException hibernateOnly = new DataIntegrityViolationException("duplicate",
                new ConstraintViolationException("duplicate", null, "sql", "uq_owner_phone"));
        // NOT NULL 위반: Hibernate는 칸 이름을 주지만 PostgreSQL은 제약 이름이 없음
        DataIntegrityViolationException notNull = new DataIntegrityViolationException("null value",
                new ConstraintViolationException("null value", psqlException("23502", null), "sql", "phone"));

        assertThat(ConstraintErrorMapper.map(fromJdbc).errorCode()).isEqualTo(OwnerErrorCode.OWNER_PHONE_DUPLICATED);
        assertThat(ConstraintErrorMapper.map(fromJdbc).sqlState()).isEqualTo("23505");
        assertThat(ConstraintErrorMapper.map(fromHibernate).errorCode())
                .isEqualTo(OwnerErrorCode.OWNER_PHONE_DUPLICATED);
        assertThat(ConstraintErrorMapper.map(hibernateOnly).constraintName()).isEqualTo("uq_owner_phone");
        assertThat(ConstraintErrorMapper.map(notNull).errorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT);
        assertThat(ConstraintErrorMapper.map(notNull).constraintName()).isNull();
    }

    private static void assertMapped(String constraintName, ErrorCode errorCode, Level logLevel) {
        ConstraintError error = ConstraintErrorMapper.map(constraintName, "23000");
        assertThat(error.errorCode()).as(constraintName).isEqualTo(errorCode);
        assertThat(error.logLevel()).as(constraintName).isEqualTo(logLevel);
        assertThat(error.constraintName()).isEqualTo(constraintName);
    }

    // PostgreSQL 서버 오류 형식: 필드 종류 한 글자 + 값, \0으로 구분 (S 심각도, C SQLState, M 메시지, n 제약 이름)
    private static PSQLException psqlException(String sqlState, String constraintName) {
        String fields = "SERROR\0C" + sqlState + "\0Mviolation\0" + (constraintName != null ? "n" + constraintName + "\0" : "");
        return new PSQLException(new ServerErrorMessage(fields));
    }
}
