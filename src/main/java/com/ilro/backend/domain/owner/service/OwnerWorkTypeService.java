package com.ilro.backend.domain.owner.service;

import com.ilro.backend.domain.owner.dto.WorkTypeSettings;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.entity.OwnerWorkType;
import com.ilro.backend.domain.owner.entity.WorkCategory;
import com.ilro.backend.domain.owner.exception.OwnerErrorCode;
import com.ilro.backend.domain.owner.repository.OwnerRepository;
import com.ilro.backend.domain.owner.repository.OwnerWorkTypeRepository;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// 작업 종류별 제공 여부·소요시간·자동 예약 허용 (B-05·B-08)
// 행은 지우지 않음 - 지난 문의·예약이 가리키므로 제공 안 함(isActive=false)으로만 바꿈
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerWorkTypeService {

    static final int DEFAULT_DURATION_MINUTES = 60;

    private final OwnerRepository ownerRepository;
    private final OwnerWorkTypeRepository workTypeRepository;
    private final AutoBookingSetup autoBookingSetup;

    // 4가지 작업 종류를 항상 모두 돌려줌 - 아직 저장하지 않은 종류는 제공 안 함·60분
    public WorkTypeSettings getWorkTypes(Long ownerId) {
        return toSettings(workTypeRepository.findByOwnerId(ownerId));
    }

    // 작업시간은 일정 계산 기준이라 업체 행을 잠그고 바꿈 (명세 1.7)
    @Transactional
    public WorkTypeSettings updateWorkTypes(Long ownerId, WorkTypeSettings request) {
        Map<WorkCategory, WorkTypeSettings.WorkType> requested = validate(request.workTypes());
        Owner owner = lockOwner(ownerId);
        Map<WorkCategory, OwnerWorkType> current = byCategory(workTypeRepository.findByOwnerId(ownerId));

        for (WorkCategory category : WorkCategory.values()) {
            WorkTypeSettings.WorkType item = requested.get(category);
            OwnerWorkType workType = current.get(category);
            if (item == null) {
                // 목록에서 빠진 작업은 제공 안 함 (소요시간은 남겨 둠)
                if (workType != null) {
                    workType.update(false, workType.getDurationMinutes(), false);
                }
                continue;
            }
            // 보내지 않은 값은 지금 값 유지, 처음 저장이면 기본값
            int duration = item.durationMinutes() != null ? item.durationMinutes()
                    : workType != null ? workType.getDurationMinutes() : DEFAULT_DURATION_MINUTES;
            boolean autoBookingAllowed = item.autoBookingAllowed() != null ? item.autoBookingAllowed()
                    : workType != null && workType.isAutoBookingAllowed();
            if (workType == null) {
                workType = OwnerWorkType.builder().owner(owner).workCategory(category).durationMinutes(duration).build();
                workType.update(item.isActive(), duration, autoBookingAllowed);
                workTypeRepository.save(workType);
            } else {
                workType.update(item.isActive(), duration, autoBookingAllowed);
            }
        }
        autoBookingSetup.keepMet(owner, AutoBookingRequirement.WORK_TYPE);
        return toSettings(workTypeRepository.findByOwnerId(ownerId));
    }

    private static Map<WorkCategory, WorkTypeSettings.WorkType> validate(List<WorkTypeSettings.WorkType> items) {
        Map<WorkCategory, WorkTypeSettings.WorkType> requested = new EnumMap<>(WorkCategory.class);
        List<FieldErrorResponse> errors = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            WorkTypeSettings.WorkType item = items.get(i);
            if (requested.putIfAbsent(item.workCategory(), item) != null) {
                throw new BusinessException(OwnerErrorCode.WORK_TYPE_DUPLICATED);
            }
            if (!item.isActive() && Boolean.TRUE.equals(item.autoBookingAllowed())) {
                errors.add(new FieldErrorResponse("workTypes[" + i + "].autoBookingAllowed",
                        "제공하지 않는 작업은 자동 예약을 허용할 수 없습니다"));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, errors);
        }
        return requested;
    }

    private static WorkTypeSettings toSettings(List<OwnerWorkType> workTypes) {
        Map<WorkCategory, OwnerWorkType> saved = byCategory(workTypes);
        return new WorkTypeSettings(Arrays.stream(WorkCategory.values())
                .map(category -> {
                    OwnerWorkType workType = saved.get(category);
                    return workType == null
                            ? new WorkTypeSettings.WorkType(category, false, DEFAULT_DURATION_MINUTES, false)
                            : new WorkTypeSettings.WorkType(category, workType.isActive(),
                            workType.getDurationMinutes(), workType.isAutoBookingAllowed());
                })
                .toList());
    }

    private static Map<WorkCategory, OwnerWorkType> byCategory(List<OwnerWorkType> workTypes) {
        Map<WorkCategory, OwnerWorkType> map = new EnumMap<>(WorkCategory.class);
        workTypes.forEach(workType -> map.put(workType.getWorkCategory(), workType));
        return map;
    }

    private Owner lockOwner(Long ownerId) {
        return ownerRepository.findByIdForUpdate(ownerId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
}
