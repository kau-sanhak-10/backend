package com.ilro.backend.domain.inquiry.repository;
import com.ilro.backend.domain.inquiry.entity.PhotoUploadStatus;

import com.ilro.backend.domain.inquiry.entity.Photo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PhotoRepository extends JpaRepository<Photo, Long> {

    List<Photo> findByInquiryIdAndIdIn(Long inquiryId, Collection<Long> ids);

    List<Photo> findByMessageIdInOrderByIdAsc(Collection<Long> messageIds);

    // 문의당 10장 제한 - 업로드 중·완료 사진만 셈 (실패한 사진은 다시 올릴 수 있게 빼고)
    long countByInquiryIdAndUploadStatusIn(Long inquiryId, Collection<PhotoUploadStatus> statuses);
}
