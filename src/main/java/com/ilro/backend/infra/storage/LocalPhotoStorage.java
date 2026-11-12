package com.ilro.backend.infra.storage;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// 로컬·테스트용 가짜 저장소 - S3 없이 "어느 경로에 어떤 크기·형식 파일이 있다"는 기록만 메모리(Map)에 둠
// AWS 권한이 없는 로컬·CI에서도 업로드 흐름을 테스트하려고 씀 (주소는 가짜라 실제로 올리거나 볼 수는 없음)
@Component
@Profile("local")
@RequiredArgsConstructor
public class LocalPhotoStorage implements PhotoStorage {

    private static final String BASE_URL = "http://localhost/local-photo-storage/";

    private final Map<String, StoredFile> files = new ConcurrentHashMap<>();
    private final Clock clock;

    @Override
    public PresignedUpload presignUpload(String key, String contentType, long sizeBytes, Duration ttl) {
        return new PresignedUpload(BASE_URL + key, Map.of("Content-Type", contentType), clock.instant().plus(ttl));
    }

    @Override
    public Optional<StoredFile> find(String key) {
        return Optional.ofNullable(files.get(key));
    }

    @Override
    public PresignedView presignView(String key, Duration ttl) {
        return new PresignedView(BASE_URL + key, clock.instant().plus(ttl));
    }

    // 테스트에서 "브라우저가 저장소에 올렸다"를 흉내
    public void putForTest(String key, long sizeBytes, String contentType) {
        files.put(key, new StoredFile(sizeBytes, contentType));
    }
}