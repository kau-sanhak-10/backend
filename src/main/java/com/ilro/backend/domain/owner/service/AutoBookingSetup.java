package com.ilro.backend.domain.owner.service;

import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.exception.OwnerErrorCode;
import com.ilro.backend.domain.owner.repository.OwnerBusinessHourRepository;
import com.ilro.backend.domain.owner.repository.OwnerServiceAreaRepository;
import com.ilro.backend.domain.owner.repository.OwnerWorkTypeRepository;
import com.ilro.backend.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

// 자동 예약 필수 설정 검사 - 켤 때 전부 확인하고, 켜진 동안에는 설정을 바꿀 때 그 항목이 빠지지 않게 막음
// (자동으로 끄지 않음 - 사장님이 직접 끈 뒤 바꾸도록 422로 알려 줌)
// 저장한 뒤 같은 트랜잭션에서 확인하므로 걸리면 저장도 되돌려짐
@Component
@RequiredArgsConstructor
public class AutoBookingSetup {

    private final OwnerBusinessHourRepository businessHourRepository;
    private final OwnerWorkTypeRepository workTypeRepository;
    private final OwnerServiceAreaRepository serviceAreaRepository;

    public List<AutoBookingRequirement> missing(Owner owner) {
        return Arrays.stream(AutoBookingRequirement.values())
                .filter(requirement -> !isMet(owner, requirement))
                .toList();
    }

    // 자동 예약을 켤 때
    public void requireComplete(Owner owner) {
        List<AutoBookingRequirement> missing = missing(owner);
        if (!missing.isEmpty()) {
            throw incomplete(missing);
        }
    }

    // 자동 예약이 켜진 업체가 설정을 바꿀 때 - 바꾼 항목만 확인
    public void keepMet(Owner owner, AutoBookingRequirement requirement) {
        if (owner.isAutoBookingEnabled() && !isMet(owner, requirement)) {
            throw incomplete(List.of(requirement));
        }
    }

    private boolean isMet(Owner owner, AutoBookingRequirement requirement) {
        return switch (requirement) {
            case BUSINESS_HOURS -> businessHourRepository.existsOpenDay(owner.getId());
            case WORK_TYPE -> workTypeRepository.existsAutoBookable(owner.getId());
            case SERVICE_AREA -> serviceAreaRepository.existsByOwnerId(owner.getId());
            case START_ADDRESS -> owner.hasStartAddress();
        };
    }

    private static BusinessException incomplete(List<AutoBookingRequirement> missing) {
        return new BusinessException(OwnerErrorCode.AUTO_BOOKING_SETUP_INCOMPLETE, Map.of("missing", missing));
    }
}
