package com.ilro.backend.domain.owner.repository;

import com.ilro.backend.domain.owner.entity.Owner;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OwnerRepository extends JpaRepository<Owner, Long> {

    // 로그인 번호로 찾기 - phone은 숫자만으로 정규화한 값
    Optional<Owner> findByPhone(String phone);

    // 일정을 바꾸는 쓰기(예약 확정·수동 예약·변경·차단·영업시간 등)는 트랜잭션 처음에 업체 행을 잠금 (명세 1.7)
    // SELECT ... FOR UPDATE, 3초 안에 못 잡으면 PessimisticLockingFailureException → 503 SERVICE_UNAVAILABLE
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select o from Owner o where o.id = :id")
    Optional<Owner> findByIdForUpdate(@Param("id") Long id);
}
