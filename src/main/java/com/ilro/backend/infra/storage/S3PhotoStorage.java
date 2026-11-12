package com.ilro.backend.infra.storage;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

// 실제 S3 - 권한은 EC2 Role(ilro-photos-s3)에서 받음, 코드·설정에 AWS 키 없음
@Component
@Profile({"dev", "prod"})
public class S3PhotoStorage implements PhotoStorage, DisposableBean {

    private final String bucket;
    private final S3Client s3;
    private final S3Presigner presigner;

    public S3PhotoStorage(@Value("${app.photo.bucket}") String bucket,
                          @Value("${app.photo.region}") String region) {
        // 버킷 이름이 없으면 서버가 뜨지 않게 - 배포 직후 health 확인에서 바로 드러남
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("app.photo.bucket(PHOTO_BUCKET)이 비어 있음");
        }
        this.bucket = bucket;
        this.s3 = S3Client.builder().region(Region.of(region)).build();
        this.presigner = S3Presigner.builder().region(Region.of(region)).build();
    }

    @Override
    public PresignedUpload presignUpload(String key, String contentType, long sizeBytes, Duration ttl) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket).key(key).contentType(contentType).contentLength(sizeBytes)
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(ttl).putObjectRequest(put).build());
        // host·content-length는 브라우저가 알아서 붙임 (content-length는 직접 넣을 수도 없음)
        Map<String, String> headers = presigned.signedHeaders().entrySet().stream()
                .filter(e -> !e.getKey().equalsIgnoreCase("host") && !e.getKey().equalsIgnoreCase("content-length"))
                .collect(Collectors.toMap(Map.Entry::getKey, e -> String.join(",", e.getValue())));
        return new PresignedUpload(presigned.url().toString(), headers, presigned.expiration());
    }

    @Override
    public Optional<StoredFile> find(String key) {
        try {
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(new StoredFile(head.contentLength(), head.contentType()));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public PresignedView presignView(String key, Duration ttl) {
        GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(key).build();
        PresignedGetObjectRequest presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl).getObjectRequest(get).build());
        return new PresignedView(presigned.url().toString(), presigned.expiration());
    }

    @Override
    public void destroy() {
        presigner.close();
        s3.close();
    }
}