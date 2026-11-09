package com.ilro.backend.domain.inquiry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 사장님 답변 (B-15) - 직접 응대를 시작한 뒤에만 (사진 보내기는 BE-06 이후)
public record OwnerMessageRequest(
        // 화면이 만든 메시지 ID(UUID) - 같은 값으로 다시 보내면 처음 저장한 메시지를 돌려줌 (명세 1.6)
        @NotBlank(message = "필수 값입니다") @Size(max = 64, message = "64자 이하여야 합니다")
        String clientMsgId,

        @NotBlank(message = "필수 값입니다") @Size(max = 2000, message = "2000자 이하여야 합니다")
        String content) {
}
