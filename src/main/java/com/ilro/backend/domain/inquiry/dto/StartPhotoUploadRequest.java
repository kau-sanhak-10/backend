package com.ilro.backend.domain.inquiry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

// 사진 업로드 시작 (C-05) - 형식·크기 허용 여부는 서비스에서 코드별로 안내
public record StartPhotoUploadRequest(
        // image/jpeg 또는 image/png
        @NotBlank(message = "필수 값입니다") String contentType,
        @NotNull(message = "필수 값입니다") @Positive(message = "1 이상이어야 합니다") Long sizeBytes) {
}