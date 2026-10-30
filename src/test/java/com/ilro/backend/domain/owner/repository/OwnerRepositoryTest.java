package com.ilro.backend.domain.owner.repository;

import com.ilro.backend.domain.owner.entity.Owner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 업체 행 잠금(FOR UPDATE)이 실제로 걸리고, 3초 넘게 기다리면 실패하는지 확인 (명세 1.7)
// 두 트랜잭션이 서로 다른 연결로 동시에 돌아야 하므로 테스트 트랜잭션(@Transactional)을 쓰지 않고 직접 커밋·삭제
@SpringBootTest
class OwnerRepositoryTest {

    @Autowired
    private OwnerRepository ownerRepository;

    @Autowired
    private TransactionTemplate tx;

    private Long ownerId;

    @BeforeEach
    void setUp() {
        ownerId = ownerRepository.save(Owner.builder()
                .businessName("일로보일러").phone(unique("o")).linkToken(unique("link")).build()).getId();
    }

    @AfterEach
    void tearDown() {
        ownerRepository.deleteById(ownerId);
    }

    @Test
    @DisplayName("다른 트랜잭션이 업체 행을 잠그고 있으면 3초 기다린 뒤 잠금 실패 예외가 난다")
    void lockTimesOutAfterThreeSeconds() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // 먼저 잠그고, 확인이 끝날 때까지(최대 10초) 잠금을 쥐고 있음
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            assertThat(ownerRepository.findByIdForUpdate(ownerId)).isPresent();
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        long startedAt = System.nanoTime();
        try {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> ownerRepository.findByIdForUpdate(ownerId)))
                    .isInstanceOf(PessimisticLockingFailureException.class);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
        Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(waited).isBetween(Duration.ofMillis(2500), Duration.ofMillis(8000));
    }

    @Test
    @DisplayName("잠금이 풀리면 기다리던 트랜잭션이 이어서 잠근다")
    void lockIsGrantedAfterRelease() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);

        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            ownerRepository.findByIdForUpdate(ownerId);
            locked.countDown();
            sleep(Duration.ofMillis(500));
        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        Boolean found = tx.execute(status -> ownerRepository.findByIdForUpdate(ownerId).isPresent());

        holder.get(10, TimeUnit.SECONDS);
        assertThat(found).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // UNIQUE 칸이 로컬 DB에 남아 있는 데이터와 겹치지 않게 임의 값 사용
    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
