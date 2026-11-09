package com.ilro.backend.domain.ai.service;

import com.ilro.backend.domain.inquiry.entity.PhotoUploadStatus;
import com.ilro.backend.domain.inquiry.repository.PhotoRepository;
import com.ilro.backend.infra.ai.AnalyzeRequest.PhotoItem;
import com.ilro.backend.infra.storage.PhotoStorage;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// AI 분석 요청에 넣을 사진 (명세 6.2, BE-06) - 이번에 보내는 메시지에 붙은, 업로드를 마친 사진만
// 원본의 비공개 S3 임시 HTTPS 주소 - 필요한 크기로 줄이는 건 AI 서버가 받아서 함 (11/9 AI 팀 합의)
// 주소에는 임시 자격 증명이 들어 있으므로 로그·DB 기록(input_snapshot)에 남기지 않음
@Component
public class AiPhotoUrls {

    private final PhotoRepository photoRepository;
    private final PhotoStorage storage;
    private final Duration aiTtl;

    public AiPhotoUrls(PhotoRepository photoRepository, PhotoStorage storage,
                       @Value("${app.photo.ai-ttl}") Duration aiTtl) {
        this.photoRepository = photoRepository;
        this.storage = storage;
        this.aiTtl = aiTtl;
    }

    public List<PhotoItem> of(Collection<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return List.of();
        }
        return photoRepository.findByMessageIdInOrderByIdAsc(messageIds).stream()
                .filter(photo -> photo.getUploadStatus() == PhotoUploadStatus.DONE)
                .map(photo -> new PhotoItem(photo.getId(), photo.getMessage().getId(),
                        storage.presignView(photo.getS3KeyOriginal(), aiTtl).url()))
                .toList();
    }
}