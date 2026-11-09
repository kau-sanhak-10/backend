package com.ilro.backend.domain.inquiry.service;

import com.ilro.backend.domain.inquiry.dto.PhotoStatusResponse;
import com.ilro.backend.domain.inquiry.dto.PhotoUploadResponse;
import com.ilro.backend.domain.inquiry.dto.PhotoUrlsResponse;
import com.ilro.backend.domain.inquiry.dto.PhotoUrlsResponse.PhotoUrl;
import com.ilro.backend.domain.inquiry.dto.StartPhotoUploadRequest;
import com.ilro.backend.domain.inquiry.entity.ConsentType;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.entity.Photo;
import com.ilro.backend.domain.inquiry.entity.PhotoUploadStatus;
import com.ilro.backend.domain.inquiry.exception.PhotoErrorCode;
import com.ilro.backend.domain.inquiry.repository.PhotoRepository;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.util.KstTime;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.infra.storage.PhotoStorage;
import com.ilro.backend.infra.storage.PhotoStorage.PresignedUpload;
import com.ilro.backend.infra.storage.PhotoStorage.PresignedView;
import com.ilro.backend.infra.storage.PhotoStorage.StoredFile;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 고객 사진 업로드·보기 (C-05, BE-06) - 파일은 S3로 직접, 서버는 검사·주소 발급·결과 확인만
// 문의 토큰은 그 문의 사진에만 (InquiryAccess), 다른 문의 사진은 있다는 사실도 숨기고 404
@Service
public class CustomerPhotoService {

    // 장당 10MB는 화면과 같은 기준 (10,000,000바이트)
    static final long MAX_BYTES = 10_000_000L;
    static final int MAX_PER_INQUIRY = 10;
    private static final Map<String, String> EXTENSIONS = Map.of("image/jpeg", "jpg", "image/png", "png");

    private final InquiryAccess inquiryAccess;
    private final PhotoRepository photoRepository;
    private final PhotoStorage storage;
    private final Clock clock;
    private final Duration uploadTtl;
    private final Duration viewTtl;

    public CustomerPhotoService(InquiryAccess inquiryAccess, PhotoRepository photoRepository, PhotoStorage storage,
                                Clock clock,
                                @Value("${app.photo.upload-ttl}") Duration uploadTtl,
                                @Value("${app.photo.view-ttl}") Duration viewTtl) {
        this.inquiryAccess = inquiryAccess;
        this.photoRepository = photoRepository;
        this.storage = storage;
        this.clock = clock;
        this.uploadTtl = uploadTtl;
        this.viewTtl = viewTtl;
    }

    // 1. 업로드 시작 - 검사 후 사진 행(UPLOADING)을 만들고 S3 업로드 주소를 줌
    @Transactional
    public PhotoUploadResponse startUpload(AuthPrincipal principal, Long inquiryId, StartPhotoUploadRequest request) {
        // 같은 문의에서 동시에 여러 장을 올려도 10장을 넘지 않게 문의 행을 잠그고 셈
        Inquiry inquiry = inquiryAccess.lock(principal, inquiryId);
        inquiryAccess.checkWritable(inquiry);
        inquiryAccess.requireConsent(inquiry, ConsentType.PHOTO);

        String contentType = request.contentType().trim().toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.containsKey(contentType)) {
            throw new BusinessException(PhotoErrorCode.PHOTO_TYPE_NOT_ALLOWED);
        }
        if (request.sizeBytes() > MAX_BYTES) {
            throw new BusinessException(PhotoErrorCode.PHOTO_TOO_LARGE);
        }
        // 업로드 주소가 만료된 채 남은 UPLOADING은 버려진 업로드라 세지 않음 (올리다 그만둬도 자리가 풀림)
        if (isFull(inquiryId)) {
            throw new BusinessException(PhotoErrorCode.PHOTO_LIMIT_EXCEEDED);
        }

        // 원래 파일 이름은 쓰지 않음 (이름에 개인정보가 들어 있을 수 있음)
        String key = "inquiries/" + inquiryId + "/" + UUID.randomUUID() + "." + EXTENSIONS.get(contentType);
        Photo photo = photoRepository.save(Photo.builder()
                .inquiry(inquiry).s3KeyOriginal(key).mimeType(contentType).sizeBytes(request.sizeBytes())
                .build());
        PresignedUpload upload = storage.presignUpload(key, contentType, request.sizeBytes(), uploadTtl);
        return new PhotoUploadResponse(photo.getId(), upload.url(), "PUT", upload.headers(),
                KstTime.of(upload.expiresAt()));
    }

    // 2. 업로드 완료 - S3에 실제로 신고한 크기·형식으로 올라왔는지 확인
    // 이미 끝난 사진은 그 결과를 그대로 돌려줌 (같은 요청을 다시 보내도 안전)
    @Transactional
    public PhotoStatusResponse complete(AuthPrincipal principal, Long inquiryId, Long photoId) {
        // 업로드 시작과 같은 잠금 - 만료된 사진을 완료할 때 장수를 다시 세는 동안 다른 업로드가 끼지 않게
        inquiryAccess.lock(principal, inquiryId);
        Photo photo = findPhoto(inquiryId, photoId);
        if (photo.getUploadStatus() == PhotoUploadStatus.UPLOADING) {
            Optional<StoredFile> file = storage.find(photo.getS3KeyOriginal());
            if (file.isPresent()) {
                if (file.get().sizeBytes() != photo.getSizeBytes()
                        || !photo.getMimeType().equalsIgnoreCase(file.get().contentType())) {
                    photo.markFailed();
                } else if (isUploadExpired(photo) && isFull(inquiryId)) {
                    // 만료돼서 자리를 내준 사진 - 그 사이 다른 사진이 자리를 채웠으면 받지 않음
                    photo.markFailed();
                } else {
                    photo.markDone();
                }
            } else if (isUploadExpired(photo)) {
                // 업로드 주소가 만료됐는데 파일이 없음 - 더 기다려도 안 올라오므로 처음부터 다시
                photo.markFailed();
            } else {
                // 아직 올리는 중일 수 있음 - 상태는 그대로, 업로드가 끝나면 다시 부르면 됨
                throw new BusinessException(PhotoErrorCode.PHOTO_NOT_UPLOADED);
            }
        }
        return new PhotoStatusResponse(photo.getId(), photo.getUploadStatus());
    }

    // 3. 사진 보기 - 업로드를 마친 이 문의 사진만, 짧게 만료되는 주소
    @Transactional(readOnly = true)
    public PhotoUrlsResponse viewUrls(AuthPrincipal principal, Long inquiryId, List<Long> photoIds) {
        inquiryAccess.find(principal, inquiryId);
        List<Long> ids = photoIds.stream().distinct().toList();
        List<Photo> photos = photoRepository.findByInquiryIdAndIdIn(inquiryId, ids);
        // 하나라도 없거나·다른 문의 것이거나·아직 안 올라온 사진이면 전체를 거절 (일부만 보여 주지 않음)
        if (photos.size() != ids.size()
                || !photos.stream().allMatch(photo -> photo.getUploadStatus() == PhotoUploadStatus.DONE)) {
            throw new BusinessException(PhotoErrorCode.PHOTO_NOT_FOUND);
        }
        return new PhotoUrlsResponse(photos.stream()
                .map(photo -> {
                    PresignedView view = storage.presignView(photo.getS3KeyOriginal(), viewTtl);
                    return new PhotoUrl(photo.getId(), view.url(), KstTime.of(view.expiresAt()));
                })
                .toList());
    }

    private Photo findPhoto(Long inquiryId, Long photoId) {
        return photoRepository.findByInquiryIdAndIdIn(inquiryId, List.of(photoId)).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(PhotoErrorCode.PHOTO_NOT_FOUND));
    }

    // 업로드 시작 후 업로드 주소 유효시간이 지났는지
    private boolean isUploadExpired(Photo photo) {
        return !photo.getCreatedAt().plus(uploadTtl).isAfter(clock.instant());
    }

    // 이 문의 사진이 꽉 찼는지 - DONE + 아직 유효한 UPLOADING (만료된 UPLOADING은 자리 없음)
    private boolean isFull(Long inquiryId) {
        long active = photoRepository.countActive(inquiryId, clock.instant().minus(uploadTtl),
                PhotoUploadStatus.DONE, PhotoUploadStatus.UPLOADING);
        return active >= MAX_PER_INQUIRY;
    }
}