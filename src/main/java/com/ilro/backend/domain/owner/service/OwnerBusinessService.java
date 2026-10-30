package com.ilro.backend.domain.owner.service;

import com.ilro.backend.domain.owner.dto.BusinessInfoRequest;
import com.ilro.backend.domain.owner.dto.BusinessInfoResponse;
import com.ilro.backend.domain.owner.dto.IntakeResponse;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.repository.OwnerRepository;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.common.util.PhoneNumbers;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

// 업체 정보·신규 접수 중지 (B-05·B-09)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerBusinessService {

    private final OwnerRepository ownerRepository;
    private final AutoBookingSetup autoBookingSetup;

    public BusinessInfoResponse getBusiness(Long ownerId) {
        return BusinessInfoResponse.from(findOwner(ownerId));
    }

    // 출발지는 이동시간 계산 기준이라 일정 쓰기처럼 업체 행을 잠그고 바꿈 (명세 1.7)
    @Transactional
    public BusinessInfoResponse updateBusiness(Long ownerId, BusinessInfoRequest request) {
        List<FieldErrorResponse> errors = new ArrayList<>();
        String contactPhone = PhoneNumbers.normalize(request.contactPhone());
        if (contactPhone != null && !PhoneNumbers.isContactNumber(contactPhone)) {
            errors.add(new FieldErrorResponse("contactPhone", "전화번호 형식이 아닙니다"));
        }
        boolean hasAddress = request.address() != null;
        if (hasAddress != (request.lat() != null) || hasAddress != (request.lng() != null)) {
            errors.add(new FieldErrorResponse("address", "출발지 주소와 좌표(lat·lng)는 함께 보내거나 모두 null로 보내 주세요"));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, errors);
        }

        Owner owner = lockOwner(ownerId);
        owner.updateBusinessInfo(request.businessName().strip(), contactPhone,
                hasAddress ? request.address().strip() : null, request.lat(), request.lng(),
                request.customerNotice(), request.serviceDescription());
        autoBookingSetup.keepMet(owner, AutoBookingRequirement.START_ADDRESS);
        return BusinessInfoResponse.from(owner);
    }

    // 새 문의만 막음 - 진행 중인 대화·예약은 그대로 (B-09)
    @Transactional
    public IntakeResponse changeIntake(Long ownerId, boolean intakePaused) {
        Owner owner = findOwner(ownerId);
        owner.changeIntakePaused(intakePaused);
        return new IntakeResponse(owner.isIntakePaused());
    }

    private Owner findOwner(Long ownerId) {
        return ownerRepository.findById(ownerId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    private Owner lockOwner(Long ownerId) {
        return ownerRepository.findByIdForUpdate(ownerId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
}
