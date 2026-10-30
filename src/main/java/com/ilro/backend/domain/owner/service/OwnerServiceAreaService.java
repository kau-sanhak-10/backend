package com.ilro.backend.domain.owner.service;

import com.ilro.backend.domain.owner.dto.ServiceAreaSettings;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.entity.OwnerServiceArea;
import com.ilro.backend.domain.owner.exception.OwnerErrorCode;
import com.ilro.backend.domain.owner.repository.OwnerRepository;
import com.ilro.backend.domain.owner.repository.OwnerServiceAreaRepository;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 서비스 지역 (B-06) - 전체 교체: 남는 지역은 이름만 갱신, 빠진 지역은 지움, 새 지역은 추가
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerServiceAreaService {

    private final OwnerRepository ownerRepository;
    private final OwnerServiceAreaRepository serviceAreaRepository;
    private final AutoBookingSetup autoBookingSetup;

    public ServiceAreaSettings getServiceAreas(Long ownerId) {
        return toSettings(serviceAreaRepository.findByOwnerIdOrderByIdAsc(ownerId));
    }

    // 자동 예약 대상 판단에 쓰여서 다른 일정 설정처럼 업체 행을 잠그고 바꿈
    @Transactional
    public ServiceAreaSettings updateServiceAreas(Long ownerId, ServiceAreaSettings request) {
        Set<String> codes = new HashSet<>();
        for (ServiceAreaSettings.ServiceArea area : request.serviceAreas()) {
            if (!codes.add(area.regionCode())) {
                throw new BusinessException(OwnerErrorCode.SERVICE_AREA_DUPLICATED);
            }
        }

        Owner owner = lockOwner(ownerId);
        Map<String, OwnerServiceArea> removed = new LinkedHashMap<>();
        serviceAreaRepository.findByOwnerIdOrderByIdAsc(ownerId)
                .forEach(area -> removed.put(area.getRegionCode(), area));
        for (ServiceAreaSettings.ServiceArea area : request.serviceAreas()) {
            OwnerServiceArea kept = removed.remove(area.regionCode());
            if (kept != null) {
                kept.rename(area.regionName().strip());
            } else {
                serviceAreaRepository.save(OwnerServiceArea.builder()
                        .owner(owner)
                        .regionCode(area.regionCode())
                        .regionName(area.regionName().strip())
                        .build());
            }
        }
        serviceAreaRepository.deleteAll(removed.values());
        autoBookingSetup.keepMet(owner, AutoBookingRequirement.SERVICE_AREA);
        return toSettings(serviceAreaRepository.findByOwnerIdOrderByIdAsc(ownerId));
    }

    private static ServiceAreaSettings toSettings(List<OwnerServiceArea> areas) {
        return new ServiceAreaSettings(areas.stream()
                .map(area -> new ServiceAreaSettings.ServiceArea(area.getRegionCode(), area.getRegionName()))
                .toList());
    }

    private Owner lockOwner(Long ownerId) {
        return ownerRepository.findByIdForUpdate(ownerId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
}
