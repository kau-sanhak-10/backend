package com.ilro.backend.domain.owner.service;

import com.ilro.backend.domain.owner.dto.OwnerMeResponse;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.repository.OwnerRepository;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerAccountService {

    private final OwnerRepository ownerRepository;

    public OwnerMeResponse getMe(Long ownerId) {
        return OwnerMeResponse.from(findOwner(ownerId));
    }

    // 이름만 바꿈 (B-03) - 로그인 번호는 운영팀이 바꿈 (O-03)
    @Transactional
    public OwnerMeResponse updateOwnerName(Long ownerId, String ownerName) {
        Owner owner = findOwner(ownerId);
        owner.changeOwnerName(ownerName.strip());
        return OwnerMeResponse.from(owner);
    }

    private Owner findOwner(Long ownerId) {
        return ownerRepository.findById(ownerId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
}
