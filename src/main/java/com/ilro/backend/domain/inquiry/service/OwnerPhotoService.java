package com.ilro.backend.domain.inquiry.service;

import com.ilro.backend.domain.inquiry.dto.PhotoUrlsResponse.PhotoUrl;
import com.ilro.backend.domain.inquiry.entity.Photo;
import com.ilro.backend.domain.inquiry.entity.PhotoUploadStatus;
import com.ilro.backend.domain.inquiry.exception.PhotoErrorCode;
import com.ilro.backend.domain.inquiry.repository.PhotoRepository;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.util.AccessLog;
import com.ilro.backend.global.common.util.KstTime;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.infra.storage.PhotoStorage;
import com.ilro.backend.infra.storage.PhotoStorage.PresignedView;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 사장님 사진 보기 (B-13, BE-06) - 자기 업체 문의에서 고객이 보낸 사진만, 짧게 만료되는 주소
@Service
public class OwnerPhotoService {

    private final InquiryAccess inquiryAccess;
    private final PhotoRepository photoRepository;
    private final PhotoStorage storage;
    private final Duration viewTtl;

    public OwnerPhotoService(InquiryAccess inquiryAccess, PhotoRepository photoRepository, PhotoStorage storage,
                             @Value("${app.photo.view-ttl}") Duration viewTtl) {
        this.inquiryAccess = inquiryAccess;
        this.photoRepository = photoRepository;
        this.storage = storage;
        this.viewTtl = viewTtl;
    }

    @Transactional(readOnly = true)
    public PhotoUrl viewUrl(AuthPrincipal principal, Long inquiryId, Long photoId) {
        inquiryAccess.findOwned(principal, inquiryId);
        Photo photo = photoRepository.findByInquiryIdAndUploadStatusAndMessageIsNotNullOrderByIdAsc(
                        inquiryId, PhotoUploadStatus.DONE).stream()
                .filter(found -> found.getId().equals(photoId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(PhotoErrorCode.PHOTO_NOT_FOUND));

        PresignedView view = storage.presignView(photo.getS3KeyOriginal(), viewTtl);
        // 고객 사진 조회도 개인정보 접근 기록 (IF-06) - 주소 원문은 남기지 않음
        AccessLog.record(principal, "VIEW", "photo", photo.getId());
        return new PhotoUrl(photo.getId(), view.url(), KstTime.of(view.expiresAt()));
    }
}
