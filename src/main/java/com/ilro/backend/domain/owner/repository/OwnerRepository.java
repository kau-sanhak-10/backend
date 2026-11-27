package com.ilro.backend.domain.owner.repository;

import com.ilro.backend.domain.owner.entity.Owner;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

// JpaSpecificationExecutor: 운영 업체 목록(O-01)의 선택 조건(심사 상태·운영 상태·검색어)을 조합해 찾음
public interface OwnerRepository extends JpaRepository<Owner, Long>, JpaSpecificationExecutor<Owner> {

    // 로그인 번호로 찾기 - phone은 숫자만으로 정규화한 값
    Optional<Owner> findByPhone(String phone);

    // 같은 로그인 번호의 업체가 있는지 (uq_owner_phone) - 등록·번호 변경 전에 미리 확인
    boolean existsByPhone(String phone);

    // 고객 접수 링크 (C-01)
    Optional<Owner> findByLinkToken(String linkToken);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    Optional<Owner> findLockedByLinkToken(String linkToken);

    // 일정을 바꾸는 쓰기(예약 확정·수동 예약·변경·차단·영업시간 등)는 트랜잭션 처음에 업체 행을 잠금 (명세 1.7)
    // SELECT ... FOR UPDATE, 3초 안에 못 잡으면 PessimisticLockingFailureException → 503 SERVICE_UNAVAILABLE
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select o from Owner o where o.id = :id")
    Optional<Owner> findByIdForUpdate(@Param("id") Long id);
}
