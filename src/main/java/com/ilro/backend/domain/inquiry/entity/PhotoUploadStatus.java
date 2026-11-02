package com.ilro.backend.domain.inquiry.entity;

// 사진 업로드 상태 - 실패한 사진만 재전송 (C05)
public enum PhotoUploadStatus {
    UPLOADING,
    DONE,
    FAILED
}
