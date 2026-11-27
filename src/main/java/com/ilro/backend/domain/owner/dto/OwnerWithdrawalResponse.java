package com.ilro.backend.domain.owner.dto;

import com.ilro.backend.domain.owner.entity.OwnerStatus;

import java.time.OffsetDateTime;

// 사장님 탈퇴 결과 (B-04) - 이 업체의 로그인 토큰은 모두 끊김
public record OwnerWithdrawalResponse(Long ownerId, OwnerStatus status, OffsetDateTime withdrawnAt) {
}
