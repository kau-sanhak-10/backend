package com.ilro.backend.domain.inquiry.dto;

import java.time.OffsetDateTime;
import java.util.Map;

// 업로드 주소 - 화면은 uploadUrl에 PUT으로 파일을 올리고, headers를 그대로 붙인 뒤 complete를 부름
public record PhotoUploadResponse(Long photoId, String uploadUrl, String method, Map<String, String> headers,
                                  OffsetDateTime expiresAt) {
}