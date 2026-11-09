package com.ilro.backend.domain.inquiry.repository;

import com.ilro.backend.domain.inquiry.entity.HandoffRequest;
import com.ilro.backend.domain.inquiry.entity.HandoffStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface HandoffRequestRepository extends JpaRepository<HandoffRequest, Long> {

    // 진행 중(WAITING·IN_PROGRESS) 요청은 문의당 1건 (uq_handoff_request_active)
    Optional<HandoffRequest> findFirstByInquiryIdAndStatusIn(Long inquiryId, Collection<HandoffStatus> statuses);

    List<HandoffRequest> findByInquiryIdInAndStatusIn(Collection<Long> inquiryIds, Collection<HandoffStatus> statuses);

    // 사장님 상세의 요청 기록 - 최근 것부터
    List<HandoffRequest> findByInquiryIdOrderByIdDesc(Long inquiryId);

    // 사장님 홈의 대기 중 요청 수 (B-10) - 삭제된 문의 제외
    @Query("select count(h) from HandoffRequest h where h.inquiry.owner.id = :ownerId "
            + "and h.inquiry.deletedAt is null and h.status = :status")
    long countByOwner(@Param("ownerId") Long ownerId, @Param("status") HandoffStatus status);

    // 처리 상태 변경 전에 문의를 먼저 잠그려고 문의 ID만 읽음
    @Query("select h.inquiry.id from HandoffRequest h where h.id = :id")
    Optional<Long> findInquiryId(@Param("id") Long id);
}
