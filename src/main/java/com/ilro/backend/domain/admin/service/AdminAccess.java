package com.ilro.backend.domain.admin.service;

import com.ilro.backend.domain.admin.entity.Admin;
import com.ilro.backend.domain.admin.entity.AdminRole;
import com.ilro.backend.domain.admin.repository.AdminRepository;
import com.ilro.backend.domain.auth.exception.AuthErrorCode;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 운영자 권한 확인 - /api/v1/admin/** 은 인터셉터가 운영자 토큰만 통과시킴 (WebConfig)
// 여기서는 그 운영자가 아직 있는지와, SUPER 전용 기능이면 역할까지 봄 (명세 1.5·3.2)
@Component
@RequiredArgsConstructor
public class AdminAccess {

    private final AdminRepository adminRepository;

    // 요청한 운영자 - 토큰은 살아 있는데 운영자 행이 없으면 401
    public Admin require(AuthPrincipal principal) {
        return adminRepository.findById(principal.adminId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHORIZED));
    }

    // SUPER 운영자만 - 로그인 번호 변경(O-03)·접근 기록·개인정보 삭제(O-09). STAFF면 403 ADMIN_ROLE_REQUIRED
    public Admin requireSuper(AuthPrincipal principal) {
        Admin admin = require(principal);
        if (admin.getRole() != AdminRole.SUPER) {
            throw new BusinessException(AuthErrorCode.ADMIN_ROLE_REQUIRED);
        }
        return admin;
    }
}
