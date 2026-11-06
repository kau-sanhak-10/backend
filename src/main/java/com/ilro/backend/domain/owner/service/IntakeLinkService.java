package com.ilro.backend.domain.owner.service;

import com.ilro.backend.domain.owner.dto.IntakeLinkResponse;
import com.ilro.backend.domain.owner.entity.ApprovalStatus;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.exception.OwnerErrorCode;
import com.ilro.backend.domain.owner.repository.OwnerRepository;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

// 접수 링크 조회(B-11)·발급(O-04) - 고객 쪽 유효성 확인은 공개 접수 API(C-01)
@Service
public class IntakeLinkService {

    private static final String LINK_PATH = "/customer/";

    private final OwnerRepository ownerRepository;
    private final IntakeLinkTokens tokens;
    private final TransactionTemplate transactionTemplate;
    private final String baseUrl;

    public IntakeLinkService(OwnerRepository ownerRepository, IntakeLinkTokens tokens,
                             TransactionTemplate transactionTemplate,
                             @Value("${app.intake.base-url}") String baseUrl) {
        this.ownerRepository = ownerRepository;
        this.tokens = tokens;
        this.transactionTemplate = transactionTemplate;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @Transactional(readOnly = true)
    public IntakeLinkResponse getLink(Long ownerId) {
        Owner owner = ownerRepository.findById(ownerId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return toResponse(owner);
    }

    // 발급·재발급 - 토큰이 우연히 겹치면(uq_owner_link_token) 새 트랜잭션에서 한 번 더
    public IntakeLinkResponse issue(Long ownerId) {
        try {
            return transactionTemplate.execute(status -> issueOnce(ownerId));
        } catch (DataIntegrityViolationException e) {
            return transactionTemplate.execute(status -> issueOnce(ownerId));
        }
    }

    private IntakeLinkResponse issueOnce(Long ownerId) {
        Owner owner = ownerRepository.findByIdForUpdate(ownerId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (owner.getApprovalStatus() != ApprovalStatus.APPROVED) {
            throw new BusinessException(OwnerErrorCode.OWNER_NOT_APPROVED);
        }
        owner.changeLinkToken(tokens.generate());
        // 겹침을 여기서 바로 확인
        ownerRepository.flush();
        return toResponse(owner);
    }

    private IntakeLinkResponse toResponse(Owner owner) {
        return IntakeLinkResponse.ready(baseUrl + LINK_PATH + owner.getLinkToken(), owner.getBusinessName());
    }
}