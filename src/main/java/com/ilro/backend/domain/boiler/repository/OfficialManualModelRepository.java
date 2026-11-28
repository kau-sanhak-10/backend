package com.ilro.backend.domain.boiler.repository;

import com.ilro.backend.domain.boiler.entity.OfficialManualModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OfficialManualModelRepository extends JpaRepository<OfficialManualModel, Long> {

    Optional<OfficialManualModel> findByManufacturerAndModelKey(String manufacturer, String modelKey);
}
