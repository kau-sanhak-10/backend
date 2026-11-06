package com.ilro.backend.domain.inquiry.repository;

import com.ilro.backend.domain.inquiry.entity.Photo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PhotoRepository extends JpaRepository<Photo, Long> {

    List<Photo> findByInquiryIdAndIdIn(Long inquiryId, Collection<Long> ids);

    List<Photo> findByMessageIdInOrderByIdAsc(Collection<Long> messageIds);
}
