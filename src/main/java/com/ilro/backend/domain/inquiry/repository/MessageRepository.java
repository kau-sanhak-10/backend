package com.ilro.backend.domain.inquiry.repository;

import com.ilro.backend.domain.inquiry.entity.Message;
import com.ilro.backend.domain.inquiry.entity.MessageSender;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    Optional<Message> findByInquiryIdAndClientMsgId(Long inquiryId, String clientMsgId);

    // 새 메시지 가져오기 - id는 저장 순서대로 커지므로 afterId 다음부터 (명세 4.2)
    List<Message> findByInquiryIdAndIdGreaterThanOrderByIdAsc(Long inquiryId, Long afterId, Limit limit);

    // AI 분석에 보낼 최근 대화
    List<Message> findByInquiryIdOrderByIdDesc(Long inquiryId, Limit limit);

    Optional<Message> findFirstByInquiryIdAndSenderOrderByIdDesc(Long inquiryId, MessageSender sender);

    // 고객 메시지 뒤에 사장님이 답했는지 - 사장님이 답한 메시지에는 AI가 다시 답하지 않음
    boolean existsByInquiryIdAndSenderAndIdGreaterThan(Long inquiryId, MessageSender sender, Long id);

    // 문의 목록에 보여 줄 문의별 마지막 메시지 (B-12)
    @Query(value = "SELECT DISTINCT ON (m.inquiry_id) m.* FROM message m WHERE m.inquiry_id IN (:inquiryIds) "
            + "ORDER BY m.inquiry_id, m.id DESC", nativeQuery = true)
    List<Message> findLatestByInquiryIdIn(@Param("inquiryIds") Collection<Long> inquiryIds);
}
