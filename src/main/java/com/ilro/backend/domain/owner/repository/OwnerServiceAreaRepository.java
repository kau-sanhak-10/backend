package com.ilro.backend.domain.owner.repository;

import com.ilro.backend.domain.owner.entity.OwnerServiceArea;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OwnerServiceAreaRepository extends JpaRepository<OwnerServiceArea, Long> {

    List<OwnerServiceArea> findByOwnerIdOrderByIdAsc(Long ownerId);

    boolean existsByOwnerId(Long ownerId);
}
