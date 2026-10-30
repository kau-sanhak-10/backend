package com.ilro.backend.global.auth;

import com.ilro.backend.domain.auth.entity.AuthToken;
import com.ilro.backend.domain.auth.entity.AuthTokenScope;
import com.ilro.backend.domain.auth.entity.AuthTokenType;

// 요청한 주체 - 토큰 종류마다 쓰는 칸이 정해져 있음 (명세 1.5)
// 사장님: ownerId / 운영자: adminId / 문의: inquiryId / 고객: ownerId + customerPhone + scope
// 컨트롤러 메서드 파라미터로 받으면 AuthPrincipalArgumentResolver가 넣어 줌
public record AuthPrincipal(Long tokenId, AuthTokenType type, Long ownerId, Long adminId, Long inquiryId,
                            String customerPhone, AuthTokenScope scope) {

    public static AuthPrincipal from(AuthToken token) {
        return new AuthPrincipal(token.getId(), token.getTokenType(),
                token.getOwner() == null ? null : token.getOwner().getId(),
                token.getAdmin() == null ? null : token.getAdmin().getId(),
                token.getInquiry() == null ? null : token.getInquiry().getId(),
                token.getCustomerPhone(), token.getScope());
    }
}
