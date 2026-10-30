package com.ilro.backend.domain.auth.service;

import com.ilro.backend.domain.auth.exception.AuthErrorCode;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.repository.OwnerRepository;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;

// 사장님 권한을 줄 수 있는 업체인지 (명세 5.1·5.8)
// 운영팀이 승인(APPROVED)했고 이용 제한·탈퇴가 아니어야 함. READY(설정 중)는 설정을 해야 하므로 허용
// 로그인 문자 발송·로그인 확인·사장님 API 요청마다 확인 (그사이 이용 제한이 될 수 있음)
@Component
@RequiredArgsConstructor
public class OwnerAccessPolicy {

    private final OwnerRepository ownerRepository;

    public Owner check(Owner owner) {
        switch (owner.getApprovalStatus()) {
            case PENDING -> throw new BusinessException(AuthErrorCode.OWNER_APPROVAL_PENDING);
            case REJECTED -> throw new BusinessException(AuthErrorCode.OWNER_REJECTED,
                    Collections.singletonMap("rejectionReason", owner.getRejectionReason()));
            case APPROVED -> {
            }
        }
        switch (owner.getStatus()) {
            case RESTRICTED -> throw new BusinessException(AuthErrorCode.OWNER_RESTRICTED);
            case WITHDRAWN -> throw new BusinessException(AuthErrorCode.OWNER_WITHDRAWN);
            case READY, ACTIVE -> {
            }
        }
        return owner;
    }

    @Transactional(readOnly = true)
    public Owner check(Long ownerId) {
        Owner owner = ownerRepository.findById(ownerId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHORIZED));
        return check(owner);
    }
}
