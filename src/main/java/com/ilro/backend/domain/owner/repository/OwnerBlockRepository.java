package com.ilro.backend.domain.owner.repository;

import com.ilro.backend.domain.owner.entity.OwnerBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OwnerBlockRepository extends JpaRepository<OwnerBlock, Long> {

    // 기간과 겹치는 차단 시간 - 방문 가능 시간 계산 (명세 5.2)
    @Query("select b from OwnerBlock b where b.owner.id = :ownerId and b.startAt < :to and b.endAt > :from "
            + "order by b.startAt, b.id")
    List<OwnerBlock> findOverlapping(@Param("ownerId") Long ownerId, @Param("from") Instant from,
                                     @Param("to") Instant to);
}
