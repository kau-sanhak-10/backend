package com.ilro.backend.domain.customer.repository;

import com.ilro.backend.domain.customer.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    // 업체별 고객은 번호로 하나 (uq_customer_owner_phone) - 같은 번호 문의 두 건이 동시에 저장해도 행은 하나만 생김
    // 이미 있으면 그대로 두고, 이름은 findByOwnerIdAndPhone으로 읽은 Entity에서 바꿈
    @Modifying
    @Query(value = "INSERT INTO customer (owner_id, phone) VALUES (:ownerId, :phone) "
            + "ON CONFLICT (owner_id, phone) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("ownerId") Long ownerId, @Param("phone") String phone);

    Optional<Customer> findByOwnerIdAndPhone(Long ownerId, String phone);

    // 이 문의 말고는 쓰는 곳이 없는 고객인지 - 다른 문의·예약(사장님 수동 예약 포함)이 연결돼 있으면 false
    // 번호 인증 전에는 이런 고객만 이름을 바꾸고 보여 줌 (남의 번호를 넣어 기존 고객 이름을 바꾸거나 읽지 못하게)
    @Query(value = "SELECT NOT EXISTS (SELECT 1 FROM inquiry WHERE customer_id = :customerId AND id <> :inquiryId) "
            + "AND NOT EXISTS (SELECT 1 FROM reservation WHERE customer_id = :customerId "
            + "AND (inquiry_id IS NULL OR inquiry_id <> :inquiryId))", nativeQuery = true)
    boolean isUsedOnlyBy(@Param("customerId") Long customerId, @Param("inquiryId") Long inquiryId);
}
