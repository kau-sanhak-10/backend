package com.ilro.backend.domain.inquiry.repository;

import com.ilro.backend.domain.inquiry.entity.InquiryField;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface InquiryFieldRepository extends JpaRepository<InquiryField, Long> {

    @Query("select f from InquiryField f where f.inquiry.id = :inquiryId and f.isCurrent = true order by f.id")
    List<InquiryField> findCurrent(@Param("inquiryId") Long inquiryId);

    // 새 값을 넣기 전에 이전 현재값을 이력으로 내림 - 항목별 현재값 1개 (uq_inquiry_field_current)
    // Hibernate는 INSERT를 UPDATE보다 먼저 보내므로 Entity 수정이 아니라 일괄 UPDATE로 먼저 실행
    @Modifying
    @Query("update InquiryField f set f.isCurrent = false "
            + "where f.inquiry.id = :inquiryId and f.fieldKey in :fieldKeys and f.isCurrent = true")
    int retireCurrent(@Param("inquiryId") Long inquiryId, @Param("fieldKeys") Collection<String> fieldKeys);

    // 현재값이 이 상태·이유인 항목을 가진 문의
    @Query("select distinct f.inquiry.id from InquiryField f where f.inquiry.id in :inquiryIds and f.isCurrent = true "
            + "and f.fieldKey in :fieldKeys and f.status = :status and f.unconfirmedReason in :reasons")
    List<Long> findInquiryIdsWith(@Param("inquiryIds") Collection<Long> inquiryIds,
                                  @Param("fieldKeys") Collection<String> fieldKeys,
                                  @Param("status") InquiryFieldStatus status,
                                  @Param("reasons") Collection<String> reasons);

    // 항목별 현재값과 수정 이력 (B-13) - 오래된 것부터
    @Query("select f from InquiryField f where f.inquiry.id = :inquiryId order by f.id")
    List<InquiryField> findAllOf(@Param("inquiryId") Long inquiryId);
}
