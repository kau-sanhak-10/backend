package com.ilro.backend.global.exception;

import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.exception.OwnerErrorCode;
import com.ilro.backend.domain.reservation.exception.ReservationErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

// 대응표(ConstraintErrorMapper)가 실제 스키마·실제 DB 오류와 맞는지 확인 (테스트마다 롤백)
@SpringBootTest
@Transactional
class ConstraintErrorMapperSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager em;

    @Test
    @DisplayName("대응표에 적은 제약 이름이 스키마에 모두 있다 (마이그레이션에서 이름을 바꾸면 깨짐)")
    void mappedConstraintNamesExistInSchema() {
        // 부분 UNIQUE는 제약이 아니라 색인이라 둘 다 봄
        Set<String> schemaNames = new HashSet<>(jdbc.queryForList(
                "SELECT c.conname FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace "
                        + "WHERE n.nspname = 'public'",
                String.class));
        schemaNames.addAll(jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class));

        assertThat(schemaNames).containsAll(ConstraintErrorMapper.mappedConstraintNames());
    }

    @Test
    @DisplayName("JDBC로 실제 제약을 어기면 제약 이름을 읽어 정해진 코드로 바꾼다")
    void mapsRealViolationsFromJdbc() {
        long ownerId = insertOwner(unique("o"));
        long otherOwnerId = insertOwner(unique("o"));
        long otherCustomerId = jdbc.queryForObject(
                "INSERT INTO customer (owner_id, phone) VALUES (?, ?) RETURNING id", Long.class,
                otherOwnerId, unique("c"));
        long reservationId = insertConfirmedReservation(ownerId, "10:00", "11:00");
        insertChange(reservationId);
        String phone = jdbc.queryForObject("SELECT phone FROM owner WHERE id = ?", String.class, ownerId);

        // 겹침 금지(EXCLUDE, SQLState 23P01)
        assertThat(mapViolation("INSERT INTO reservation (owner_id, start_at, end_at, status) VALUES (" + ownerId
                + ", '2099-01-05T10:30:00+09:00', '2099-01-05T11:30:00+09:00', 'CONFIRMED')"))
                .isEqualTo(ReservationErrorCode.SLOT_UNAVAILABLE);
        // 부분 UNIQUE 색인
        assertThat(mapViolation("INSERT INTO reservation_change (reservation_id, change_type, requested_by, "
                + "prev_start_at, prev_end_at, status, base_version) VALUES (" + reservationId + ", 'CHANGE', "
                + "'OWNER', '2099-01-05T10:00:00+09:00', '2099-01-05T11:00:00+09:00', 'PROPOSED', 0)"))
                .isEqualTo(ReservationErrorCode.CHANGE_IN_PROGRESS);
        // UNIQUE 제약
        assertThat(mapViolation("INSERT INTO owner (business_name, phone, link_token) VALUES ('보일러', '" + phone
                + "', '" + unique("link") + "')"))
                .isEqualTo(OwnerErrorCode.OWNER_PHONE_DUPLICATED);
        // 다른 업체 고객을 예약에 연결 (복합 FK)
        assertThat(mapViolation("INSERT INTO reservation (owner_id, customer_id, start_at, end_at) VALUES (" + ownerId
                + ", " + otherCustomerId + ", '2099-01-06T10:00:00+09:00', '2099-01-06T11:00:00+09:00')"))
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
        // 없는 대상 (FK)
        assertThat(mapViolation("INSERT INTO customer (owner_id, phone) VALUES (-1, '" + unique("c") + "')"))
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
        // CHECK
        assertThat(mapViolation("INSERT INTO reservation (owner_id, start_at, end_at) VALUES (" + ownerId
                + ", '2099-01-05T12:00:00+09:00', '2099-01-05T11:00:00+09:00')"))
                .isEqualTo(CommonErrorCode.INVALID_INPUT);
        // NOT NULL (제약 이름 없음)
        assertThat(mapViolation("INSERT INTO owner (business_name, phone) VALUES ('보일러', '" + unique("o") + "')"))
                .isEqualTo(CommonErrorCode.INVALID_INPUT);
        // 대응표에 없는 제약
        jdbc.update("INSERT INTO admin (name, phone) VALUES ('운영자', ?)", phone);
        assertThat(mapViolation("INSERT INTO admin (name, phone) VALUES ('운영자2', '" + phone + "')"))
                .isEqualTo(CommonErrorCode.DATA_CONFLICT);
    }

    @Test
    @DisplayName("JPA(Hibernate)로 저장하다 제약을 어겨도 같은 코드로 바꾼다")
    void mapsRealViolationFromHibernate() {
        String phone = unique("o");
        em.persist(Owner.builder().businessName("일로보일러").phone(phone).linkToken(unique("link")).build());

        // IDENTITY 키라 persist 순간에 INSERT가 나감
        PersistenceException insertError = catchThrowableOfType(PersistenceException.class, () -> em.persist(
                Owner.builder().businessName("다른보일러").phone(phone).linkToken(unique("link")).build()));
        // 리포지토리·트랜잭션 커밋에서 Spring이 하는 것과 같은 변환
        DataAccessException translated = new HibernateJpaDialect().translateExceptionIfPossible(insertError);

        assertThat(translated).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(ConstraintErrorMapper.map((DataIntegrityViolationException) translated).errorCode())
                .isEqualTo(OwnerErrorCode.OWNER_PHONE_DUPLICATED);
    }

    // 제약에 걸리면 트랜잭션을 더 쓸 수 없으므로 저장점으로 되돌려 다음 확인을 이어 감
    private ErrorCode mapViolation(String sql) {
        jdbc.execute("SAVEPOINT before_violation");
        DataIntegrityViolationException e = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> jdbc.execute(sql));
        jdbc.execute("ROLLBACK TO SAVEPOINT before_violation");
        assertThat(e).as(sql).isNotNull();
        return ConstraintErrorMapper.map(e).errorCode();
    }

    private long insertOwner(String phone) {
        return jdbc.queryForObject(
                "INSERT INTO owner (business_name, phone, link_token) VALUES ('테스트보일러', ?, ?) RETURNING id",
                Long.class, phone, unique("link"));
    }

    // 시각은 2099-01-05(월) 한국 시간 기준 "HH:mm"
    private long insertConfirmedReservation(long ownerId, String start, String end) {
        return jdbc.queryForObject(
                "INSERT INTO reservation (owner_id, start_at, end_at, status) "
                        + "VALUES (?, ?::timestamptz, ?::timestamptz, 'CONFIRMED') RETURNING id",
                Long.class, ownerId, "2099-01-05T" + start + ":00+09:00", "2099-01-05T" + end + ":00+09:00");
    }

    private void insertChange(long reservationId) {
        jdbc.update(
                "INSERT INTO reservation_change (reservation_id, change_type, requested_by, prev_start_at, prev_end_at, "
                        + "status, base_version) VALUES (?, 'CHANGE', 'CUSTOMER', "
                        + "'2099-01-05T10:00:00+09:00', '2099-01-05T11:00:00+09:00', 'REQUESTED', 0)",
                reservationId);
    }

    // UNIQUE 칸이 로컬 DB에 남아 있는 데이터와 겹치지 않게 임의 값 사용
    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
