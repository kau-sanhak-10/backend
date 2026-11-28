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

import java.time.Instant;
import java.time.OffsetDateTime;

// 인증 확인 결과 - 목적마다 채우는 칸이 다름 (명세 5.1), 빈 칸은 응답에서 뺌
// 사장님·운영자 로그인: accessToken·expiresAt·owner/admin
// 예약 전 번호 확인(CUSTOMER_VERIFY): inquiryId·verifiedAt
// 상담 복구(INQUIRY_ACCESS)·예약 조회(RESERVATION_LOOKUP): 고객 토큰(cus_) accessToken·expiresAt
// 운영팀의 사장님 번호 확인(OWNER_SIGNUP): verifiedAt만
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConfirmVerificationResponse(VerificationPurpose purpose, String accessToken, OffsetDateTime expiresAt,
                                          OwnerLogin owner, AdminLogin admin, Long inquiryId,
                                          OffsetDateTime verifiedAt) {

    public record OwnerLogin(Long ownerId, String businessName, ApprovalStatus approvalStatus, OwnerStatus status) {
    }

    public record AdminLogin(Long adminId, String name, AdminRole role) {
    }

    public static ConfirmVerificationResponse ownerLogin(IssuedToken token, Owner owner) {
        return new ConfirmVerificationResponse(VerificationPurpose.OWNER_LOGIN, token.token(),
                KstTime.of(token.expiresAt()),
                new OwnerLogin(owner.getId(), owner.getBusinessName(), owner.getApprovalStatus(), owner.getStatus()),
                null, null, null);
    }

    public static ConfirmVerificationResponse adminLogin(IssuedToken token, Admin admin) {
        return new ConfirmVerificationResponse(VerificationPurpose.ADMIN_LOGIN, token.token(),
                KstTime.of(token.expiresAt()), null,
                new AdminLogin(admin.getId(), admin.getName(), admin.getRole()), null, null);
    }

    public static ConfirmVerificationResponse customerVerified(Long inquiryId, Instant verifiedAt) {
        return new ConfirmVerificationResponse(VerificationPurpose.CUSTOMER_VERIFY, null, null, null, null, inquiryId,
                KstTime.of(verifiedAt));
    }

    public static ConfirmVerificationResponse customerToken(VerificationPurpose purpose, IssuedToken token) {
        return new ConfirmVerificationResponse(purpose, token.token(), KstTime.of(token.expiresAt()), null, null,
                null, null);
    }

    // 번호 확인뿐인 인증 (OWNER_SIGNUP) - 토큰 없이 확인 시각만
    public static ConfirmVerificationResponse phoneConfirmed(VerificationPurpose purpose, Instant verifiedAt) {
        return new ConfirmVerificationResponse(purpose, null, null, null, null, null, KstTime.of(verifiedAt));
    }
}
