package com.ilro.backend.domain.admin.dto;

import com.ilro.backend.domain.owner.entity.OwnerStatus;
import jakarta.validation.constraints.NotNull;

// 운영 상태 변경 (O-02) - READY·ACTIVE·RESTRICTED만. 탈퇴(WITHDRAWN)는 사장님 탈퇴 요청으로만 됨
public record ChangeOwnerStatusRequest(@NotNull(message = "필수 값입니다") OwnerStatus status) {
}
