package com.ilro.backend.infra.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

// 사진 파일 저장소 - 파일은 서버를 거치지 않고 브라우저·AI 서버가 임시 주소로 직접 주고받음
// dev·prod: S3PhotoStorage / local·테스트: LocalPhotoStorage (S3 없이 메모리에서 흉내)
public interface PhotoStorage {

    // 업로드 주소 - 형식·크기를 서명에 넣어, 신고와 다른 파일은 저장소가 거절
    PresignedUpload presignUpload(String key, String contentType, long sizeBytes, Duration ttl);

    // 실제로 올라간 파일의 크기·형식 (아직 없으면 empty)
    Optional<StoredFile> find(String key);

    // 보기 주소
    PresignedView presignView(String key, Duration ttl);

    // headers: 브라우저가 업로드할 때 그대로 붙여야 하는 헤더 (Content-Type 등)
    record PresignedUpload(String url, Map<String, String> headers, Instant expiresAt) {
    }

    record StoredFile(long sizeBytes, String contentType) {
    }

    record PresignedView(String url, Instant expiresAt) {
    }
}