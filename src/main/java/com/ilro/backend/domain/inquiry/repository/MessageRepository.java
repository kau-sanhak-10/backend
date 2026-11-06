package com.ilro.backend.domain.inquiry.repository;

import com.ilro.backend.domain.inquiry.entity.Message;
import com.ilro.backend.domain.inquiry.entity.MessageSender;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    Optional<Message> findByInquiryIdAndClientMsgId(Long inquiryId, String clientMsgId);

    // 새 메시지 가져오기 - id는 저장 순서대로 커지므로 afterId 다음부터 (명세 4.2)
    List<Message> findByInquiryIdAndIdGreaterThanOrderByIdAsc(Long inquiryId, Long afterId, Limit limit);

    // AI 분석에 보낼 최근 대화
    List<Message> findByInquiryIdOrderByIdDesc(Long inquiryId, Limit limit);

    Optional<Message> findFirstByInquiryIdAndSenderOrderByIdDesc(Long inquiryId, MessageSender sender);
}
