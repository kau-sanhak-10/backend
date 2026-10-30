package com.ilro.backend.domain.owner.repository;

import com.ilro.backend.domain.owner.entity.OwnerWorkType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OwnerWorkTypeRepository extends JpaRepository<OwnerWorkType, Long> {

    List<OwnerWorkType> findByOwnerId(Long ownerId);

    // 자동 예약 필수 설정 - 제공하면서 자동 예약도 허용한 작업이 하나라도 있는지
    @Query("select count(w) > 0 from OwnerWorkType w "
            + "where w.owner.id = :ownerId and w.isActive = true and w.autoBookingAllowed = true")
    boolean existsAutoBookable(@Param("ownerId") Long ownerId);
}
