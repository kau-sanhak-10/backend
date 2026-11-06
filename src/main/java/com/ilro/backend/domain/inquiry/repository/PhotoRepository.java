package com.ilro.backend.domain.inquiry.repository;

import com.ilro.backend.domain.inquiry.entity.Photo;
import com.ilro.backend.domain.inquiry.entity.PhotoUploadStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PhotoRepository extends JpaRepository<Photo, Long> {

    List<Photo> findByInquiryIdAndIdIn(Long inquiryId, Collection<Long> ids);

    List<Photo> findByMessageIdInOrderByIdAsc(Collection<Long> messageIds);

    // 사장님 상세의 사진 목록 - 업로드를 마친 것만 (B-13)
    List<Photo> findByInquiryIdAndUploadStatusOrderByIdAsc(Long inquiryId, PhotoUploadStatus uploadStatus);
}
