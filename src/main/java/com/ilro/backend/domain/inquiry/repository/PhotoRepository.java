package com.ilro.backend.domain.inquiry.repository;
import com.ilro.backend.domain.inquiry.entity.PhotoUploadStatus;

import com.ilro.backend.domain.inquiry.entity.Photo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface PhotoRepository extends JpaRepository<Photo, Long> {

    List<Photo> findByInquiryIdAndIdIn(Long inquiryId, Collection<Long> ids);

    List<Photo> findByMessageIdInOrderByIdAsc(Collection<Long> messageIds);

    // 문의당 10장 제한 - 완료 사진 + 아직 올리는 중인 사진만 셈
    // 업로드 주소가 만료된 채 남은 UPLOADING(uploadingSince 이전)은 버려진 업로드라 세지 않음
    @Query("select count(p) from Photo p where p.inquiry.id = :inquiryId "
            + "and (p.uploadStatus = :done or (p.uploadStatus = :uploading and p.createdAt > :uploadingSince))")
    long countActive(@Param("inquiryId") Long inquiryId, @Param("uploadingSince") Instant uploadingSince,
                     @Param("done") PhotoUploadStatus done, @Param("uploading") PhotoUploadStatus uploading);
}
