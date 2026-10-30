package com.ilro.backend.domain.owner.service;

import com.ilro.backend.domain.owner.dto.OwnerMeResponse;
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
        return ownerRepository.findById(ownerId)
                .map(OwnerMeResponse::from)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
}
