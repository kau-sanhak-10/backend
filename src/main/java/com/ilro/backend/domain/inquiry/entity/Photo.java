package com.ilro.backend.domain.inquiry.entity;

import com.ilro.backend.global.entity.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 문의 사진 (S3)
@Getter
@Entity
@Table(name = "photo")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Photo extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inquiry_id", nullable = false)
    private Inquiry inquiry;

    // 같은 문의의 메시지만 연결 가능 (DB 복합 FK)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id")
    private Message message;

    @Column(nullable = false)
    private String s3KeyOriginal;

    // 1024px 리사이즈본 - LLM에는 이것만 전송
    private String s3KeyResized;

    // 미지원 형식·용량 초과 구분 안내 (C05)
    @Column(nullable = false, length = 50)
    private String mimeType;

    @Column(nullable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PhotoUploadStatus uploadStatus = PhotoUploadStatus.UPLOADING;

    @Builder
    private Photo(Inquiry inquiry, Message message, String s3KeyOriginal, String mimeType, long sizeBytes) {
        this.inquiry = inquiry;
        this.message = message;
        this.s3KeyOriginal = s3KeyOriginal;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
    }
}
