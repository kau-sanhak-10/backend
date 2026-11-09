package com.ilro.backend.domain.inquiry.dto;

import java.time.OffsetDateTime;
import java.util.List;

// 사진 보기 주소 - 짧게 만료되므로 화면은 만료되면 다시 요청
public record PhotoUrlsResponse(List<PhotoUrl> photos) {

    public record PhotoUrl(Long photoId, String url, OffsetDateTime expiresAt) {
    }
}