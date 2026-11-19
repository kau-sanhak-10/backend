package com.ilro.backend.domain.owner.dto;

import jakarta.validation.constraints.NotNull;

// 사장님 탈퇴 요청 (B-04) - 방금 받은 사장님 로그인 문자 인증(OWNER_LOGIN) 번호, 로그인 중이어도 다시 인증 (명세 5.1)
public record OwnerWithdrawalRequest(@NotNull Long verificationId) {
}
