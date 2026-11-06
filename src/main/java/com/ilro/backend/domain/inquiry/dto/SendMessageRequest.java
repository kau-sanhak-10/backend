package com.ilro.backend.domain.inquiry.dto;

import com.ilro.backend.global.common.util.InputRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

// 메시지 보내기 (C-04·C-07·C-09) - 내용과 사진 중 하나는 있어야 함
public record SendMessageRequest(
        // 화면이 만든 메시지 ID(UUID) - 같은 값으로 다시 보내면 처음 저장한 메시지를 돌려줌 (명세 1.6)
        @NotBlank(message = "필수 값입니다") @Size(max = 64, message = "64자 이하여야 합니다")
        String clientMsgId,

        @Pattern(regexp = InputRules.NOT_BLANK, message = InputRules.NOT_BLANK_MESSAGE)
        @Size(max = 2000, message = "2000자 이하여야 합니다")
        String content,

        // 업로드를 마친 이 상담의 사진 (사진 업로드 API는 BE-06)
        @Size(max = 10, message = "사진은 한 번에 10장까지입니다")
        List<@NotNull(message = "필수 값입니다") Long> photoIds) {
}
