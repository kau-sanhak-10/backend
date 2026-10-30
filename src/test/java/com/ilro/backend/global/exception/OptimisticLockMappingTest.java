package com.ilro.backend.global.exception;

import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.global.common.response.ApiResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

// 실제 @Version 충돌에서 Spring이 넘겨주는 예외로 예약 충돌 코드를 고르는지 확인
// 두 연결이 같은 행을 고쳐야 하므로 테스트 트랜잭션(@Transactional)을 쓰지 않고 직접 커밋·삭제
@SpringBootTest
class OptimisticLockMappingTest {

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager em;

    private Long ownerId;

    private Long reservationId;

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM reservation WHERE id = ?", reservationId);
        jdbc.update("DELETE FROM owner WHERE id = ?", ownerId);
    }

    @Test
    @DisplayName("예약을 읽은 사이 다른 요청이 먼저 고치면 409 RESERVATION_VERSION_CONFLICT")
    void reservationVersionConflict() {
        tx.executeWithoutResult(status -> {
            Owner owner = Owner.builder()
                    .businessName("일로보일러").phone(unique("o")).linkToken(unique("link")).build();
            em.persist(owner);
            Instant start = Instant.parse("2099-01-05T01:00:00Z");
            Reservation reservation = Reservation.builder()
                    .owner(owner).startAt(start).endAt(start.plus(1, ChronoUnit.HOURS)).build();
            em.persist(reservation);
            ownerId = owner.getId();
            reservationId = reservation.getId();
        });

        ObjectOptimisticLockingFailureException e = catchThrowableOfType(ObjectOptimisticLockingFailureException.class,
                () -> tx.executeWithoutResult(status -> {
                    Reservation reservation = em.find(Reservation.class, reservationId);
                    // 다른 연결에서 먼저 수정·커밋
                    CompletableFuture.runAsync(() -> jdbc.update(
                            "UPDATE reservation SET version = version + 1 WHERE id = ?", reservationId)).join();
                    ReflectionTestUtils.setField(reservation, "address", "경기 고양시 덕양구 ○○로 12");
                }));

        assertThat(e).isNotNull();
        ResponseEntity<ApiResponse<Void>> response = new GlobalExceptionHandler().handleOptimisticLock(e);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getCode()).isEqualTo("RESERVATION_VERSION_CONFLICT");
    }

    // UNIQUE 칸이 로컬 DB에 남아 있는 데이터와 겹치지 않게 임의 값 사용
    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
