package com.ilro.backend.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ilro.backend.domain.admin.entity.Admin;
import com.ilro.backend.domain.admin.entity.AdminRole;
import com.ilro.backend.domain.auth.entity.VerificationPurpose;
import com.ilro.backend.domain.auth.service.AuthTokenService.IssuedToken;
import com.ilro.backend.domain.owner.entity.ApprovalStatus;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.entity.OwnerStatus;
import com.ilro.backend.global.common.util.KstTime;

import java.time.OffsetDateTime;

// 인증 확인 결과 - 목적마다 채우는 칸이 다름 (명세 5.1), 빈 칸은 응답에서 뺌
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConfirmVerificationResponse(VerificationPurpose purpose, String accessToken, OffsetDateTime expiresAt,
                                          OwnerLogin owner, AdminLogin admin) {

    public record OwnerLogin(Long ownerId, String businessName, ApprovalStatus approvalStatus, OwnerStatus status) {
    }

    public record AdminLogin(Long adminId, String name, AdminRole role) {
    }

    public static ConfirmVerificationResponse ownerLogin(IssuedToken token, Owner owner) {
        return new ConfirmVerificationResponse(VerificationPurpose.OWNER_LOGIN, token.token(),
                KstTime.of(token.expiresAt()),
                new OwnerLogin(owner.getId(), owner.getBusinessName(), owner.getApprovalStatus(), owner.getStatus()),
                null);
    }

    public static ConfirmVerificationResponse adminLogin(IssuedToken token, Admin admin) {
        return new ConfirmVerificationResponse(VerificationPurpose.ADMIN_LOGIN, token.token(),
                KstTime.of(token.expiresAt()), null,
                new AdminLogin(admin.getId(), admin.getName(), admin.getRole()));
    }
}
