package com.ilro.backend.domain.boiler.repository;

import com.ilro.backend.domain.boiler.entity.OfficialManualErrorCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OfficialManualErrorCodeRepository extends JpaRepository<OfficialManualErrorCode, Long> {

    Optional<OfficialManualErrorCode> findByManualIdAndCode(Long manualId, String code);
}
