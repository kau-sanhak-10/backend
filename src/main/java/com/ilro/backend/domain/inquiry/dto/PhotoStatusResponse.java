package com.ilro.backend.domain.inquiry.dto;

import com.ilro.backend.domain.inquiry.entity.PhotoUploadStatus;

// DONE이면 메시지 photoIds로 보낼 수 있음, FAILED면 업로드 시작부터 다시
public record PhotoStatusResponse(Long photoId, PhotoUploadStatus uploadStatus) {
}