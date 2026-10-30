package com.ilro.backend.domain.owner.repository;

import com.ilro.backend.domain.owner.entity.OwnerBusinessHour;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OwnerBusinessHourRepository extends JpaRepository<OwnerBusinessHour, Long> {

    List<OwnerBusinessHour> findByOwnerIdOrderByDayOfWeekAsc(Long ownerId);

    // 자동 예약 필수 설정 - 문을 여는 요일이 하루라도 있는지
    @Query("select count(h) > 0 from OwnerBusinessHour h where h.owner.id = :ownerId and h.isClosed = false")
    boolean existsOpenDay(@Param("ownerId") Long ownerId);
}
