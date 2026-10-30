package com.ilro.backend.domain.inquiry.repository;

import com.ilro.backend.domain.inquiry.entity.Inquiry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InquiryRepository extends JpaRepository<Inquiry, Long> {

    // AI 입력 순번 +1 (명세 1.7·6.1) - 일괄 UPDATE라 @Version(version)은 오르지 않음
    // 이미 읽어 둔 Inquiry의 aiInputSeq 값은 바뀌지 않으므로, 새 값은 findAiInputSeq로 다시 읽음
    @Modifying
    @Query("update Inquiry i set i.aiInputSeq = i.aiInputSeq + 1 where i.id = :id")
    int incrementAiInputSeq(@Param("id") Long id);

    @Query("select i.aiInputSeq from Inquiry i where i.id = :id")
    Optional<Long> findAiInputSeq(@Param("id") Long id);
}
