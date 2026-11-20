package com.ilro.backend.domain.admin.dto;

import com.ilro.backend.global.common.util.InputRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 운영팀의 업체 신청 등록 (B-01·O-02, 명세 5.8) - 결과는 승인 대기(PENDING)·설정 중(READY)·자동 예약 꺼짐
// phone: 사장님 로그인 번호 (010-1234-5678·01012345678 모두 받음)
// phoneVerificationId: 운영자가 그 번호로 보낸 OWNER_SIGNUP 인증 (그 번호가 문자를 받는다는 확인)
// address: 좌표 없이 주소만 - 출발지 좌표는 승인 뒤 사장님이 업체 정보(B-05)에서 채움
public record RegisterOwnerRequest(
        @NotBlank(message = "필수 값입니다") @Size(max = 100, message = "100자 이하여야 합니다")
        String businessName,
        @Pattern(regexp = InputRules.NOT_BLANK, message = InputRules.NOT_BLANK_MESSAGE)
        @Size(max = 50, message = "50자 이하여야 합니다")
        String ownerName,
        @NotBlank(message = "필수 값입니다") String phone,
        @NotNull(message = "필수 값입니다") Long phoneVerificationId,
        @Pattern(regexp = InputRules.NOT_BLANK, message = InputRules.NOT_BLANK_MESSAGE)
        String contactPhone,
        @Pattern(regexp = InputRules.NOT_BLANK, message = InputRules.NOT_BLANK_MESSAGE)
        @Size(max = 255, message = "255자 이하여야 합니다")
        String address,
        @Pattern(regexp = InputRules.NOT_BLANK, message = InputRules.NOT_BLANK_MESSAGE)
        @Size(max = 255, message = "255자 이하여야 합니다")
        String serviceDescription) {
}
