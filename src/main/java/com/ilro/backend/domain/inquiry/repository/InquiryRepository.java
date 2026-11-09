package com.ilro.backend.domain.inquiry.repository;

import com.ilro.backend.domain.inquiry.entity.HandlerMode;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.entity.InquiryStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface InquiryRepository extends JpaRepository<Inquiry, Long> {

    // 문의 쓰기(메시지·방문정보·접수정보)는 문의 행을 잠그고 차례로 처리 - 같은 clientMsgId 동시 재전송도 하나만 저장
    // 3초 안에 못 잡으면 503 SERVICE_UNAVAILABLE (명세 3.10)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select i from Inquiry i where i.id = :id")
    Optional<Inquiry> findByIdForUpdate(@Param("id") Long id);

    // AI 입력 순번 +1 (명세 1.7·6.1) - 일괄 UPDATE라 @Version(version)은 오르지 않음
    // 이미 읽어 둔 Inquiry의 aiInputSeq 값은 바뀌지 않으므로, 새 값은 findAiInputSeq로 다시 읽음
    @Modifying
    @Query("update Inquiry i set i.aiInputSeq = i.aiInputSeq + 1 where i.id = :id")
    int incrementAiInputSeq(@Param("id") Long id);

    @Query("select i.aiInputSeq from Inquiry i where i.id = :id")
    Optional<Long> findAiInputSeq(@Param("id") Long id);

    // 답을 못 받은 고객 메시지가 있는 AI 응대 문의 - 마지막으로 적용한 AI 판단보다 뒤에 왔고 사장님도 답하지 않은 메시지
    // 서버 재시작·대기열 가득 참으로 놓친 분석을 다시 하려고 씀 (최근 것부터)
    @Query(value = """
            SELECT i.id FROM inquiry i
            WHERE i.deleted_at IS NULL AND i.handler_mode = 'AI' AND i.status IN ('COLLECTING', 'SCHEDULING')
              AND EXISTS (SELECT 1 FROM message m
                          WHERE m.inquiry_id = i.id AND m.sender = 'CUSTOMER'
                            AND m.created_at >= :from AND m.created_at < :to
                            AND m.id > COALESCE((SELECT MAX(d.message_id) FROM ai_decision d
                                                 WHERE d.inquiry_id = i.id AND d.status = 'SUCCESS'), 0)
                            AND NOT EXISTS (SELECT 1 FROM message o
                                            WHERE o.inquiry_id = i.id AND o.sender = 'OWNER' AND o.id > m.id))
            ORDER BY i.id DESC
            LIMIT :limit""", nativeQuery = true)
    List<Long> findWaitingForAi(@Param("from") Instant from, @Param("to") Instant to, @Param("limit") int limit);

    String OWNER_LIST_WHERE = "where i.owner.id = :ownerId and i.deletedAt is null "
            + "and (:status is null or i.status = :status) and (:handlerMode is null or i.handlerMode = :handlerMode)";

    // 사장님 문의 목록 (B-12) - 삭제된 문의 빼고, 상태·응대 주체는 보낸 것만 거름
    // 고객·작업은 목록에 바로 보여 주므로 함께 읽음 (한쪽 연결이라 쪽 나누기와 같이 써도 됨)
    @Query(value = "select i from Inquiry i left join fetch i.customer left join fetch i.workType "
            + OWNER_LIST_WHERE, countQuery = "select count(i) from Inquiry i " + OWNER_LIST_WHERE)
    Page<Inquiry> findOwnerInquiries(@Param("ownerId") Long ownerId, @Param("status") InquiryStatus status,
                                     @Param("handlerMode") HandlerMode handlerMode, Pageable pageable);

    // 사장님 홈 확인 필요 건수 (B-10)
    long countByOwnerIdAndStatusAndDeletedAtIsNull(Long ownerId, InquiryStatus status);
}
