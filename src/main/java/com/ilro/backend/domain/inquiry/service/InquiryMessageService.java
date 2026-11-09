package com.ilro.backend.domain.inquiry.service;

import com.ilro.backend.domain.ai.service.AiAnalysisRequested;
import com.ilro.backend.domain.inquiry.dto.MessagesResponse;
import com.ilro.backend.domain.inquiry.dto.MessagesResponse.MessageItem;
import com.ilro.backend.domain.inquiry.dto.SendMessageRequest;
import com.ilro.backend.domain.inquiry.dto.SendMessageResponse;
import com.ilro.backend.domain.inquiry.dto.SendMessageResponse.AiStatus;
import com.ilro.backend.domain.inquiry.entity.ConsentType;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.entity.Message;
import com.ilro.backend.domain.inquiry.entity.MessageSender;
import com.ilro.backend.domain.inquiry.entity.Photo;
import com.ilro.backend.domain.inquiry.repository.InquiryRepository;
import com.ilro.backend.domain.inquiry.repository.MessageRepository;
import com.ilro.backend.domain.inquiry.repository.PhotoRepository;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.common.util.AccessLog;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

// 대화 저장·가져오기 - 고객(C-04·C-07·C-09·C-11)·사장님 대화 보기(B-13), 명세 4.2·6.1
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InquiryMessageService {

    // 한 번에 돌려주는 메시지 수 - 화면은 2초마다 afterId 다음 것만 가져감
    private static final int PAGE_SIZE = 100;

    private final InquiryAccess inquiryAccess;
    private final InquiryRepository inquiryRepository;
    private final MessageRepository messageRepository;
    private final PhotoRepository photoRepository;
    private final ApplicationEventPublisher eventPublisher;

    // created: 새로 저장했으면 true(201), 같은 clientMsgId 재전송이면 false(200)
    public record SentMessage(SendMessageResponse response, boolean created) {
    }

    public MessagesResponse getMessages(AuthPrincipal principal, Long inquiryId, Long afterId) {
        return page(principal, inquiryAccess.find(principal, inquiryId), afterId);
    }

    // 사장님 대화 보기 (B-13·B-15) - 자기 업체 문의만
    public MessagesResponse getOwnerMessages(AuthPrincipal principal, Long inquiryId, Long afterId) {
        return page(principal, inquiryAccess.findOwned(principal, inquiryId), afterId);
    }

    private MessagesResponse page(AuthPrincipal principal, Inquiry inquiry, Long afterId) {
        List<Message> found = messageRepository.findByInquiryIdAndIdGreaterThanOrderByIdAsc(inquiry.getId(),
                afterId == null ? 0L : afterId, Limit.of(PAGE_SIZE + 1));
        boolean hasMore = found.size() > PAGE_SIZE;
        List<Message> messages = hasMore ? found.subList(0, PAGE_SIZE) : found;
        Map<Long, List<Long>> photoIds = messages.isEmpty() ? Map.of() : photoRepository
                .findByMessageIdInOrderByIdAsc(messages.stream().map(Message::getId).toList()).stream()
                .collect(Collectors.groupingBy(photo -> photo.getMessage().getId(),
                        Collectors.mapping(Photo::getId, Collectors.toList())));
        // 2초마다 오는 빈 조회까지 남기면 기록이 묻히므로 대화를 돌려줄 때만
        if (!messages.isEmpty()) {
            AccessLog.record(principal, "VIEW", "inquiry.messages", inquiry.getId());
        }
        return new MessagesResponse(messages.stream()
                .map(message -> MessageItem.of(message, photoIds.getOrDefault(message.getId(), List.of())))
                .toList(), hasMore);
    }

    // 문의 행을 잠그고 처리 - 같은 clientMsgId가 동시에 와도 하나만 저장 (uq_message_inquiry_client_msg)
    // 메시지를 넣는 다른 곳(AI 답변·사장님 답변)도 같은 잠금을 잡아야 함:
    // 잠금 없이 동시에 넣으면 id 순서와 커밋 순서가 어긋나 afterId로 가져갈 때 메시지를 건너뛸 수 있음
    @Transactional
    public SentMessage sendMessage(AuthPrincipal principal, Long inquiryId, SendMessageRequest request) {
        List<Long> photoIds = request.photoIds() == null ? List.of() : request.photoIds().stream().distinct().toList();
        if (request.content() == null && photoIds.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    List.of(new FieldErrorResponse("content", "내용이나 사진 중 하나는 보내 주세요")));
        }

        Inquiry inquiry = inquiryAccess.lock(principal, inquiryId);
        // 재전송이면 그사이 상담이 종료됐어도 처음 결과를 돌려줌
        Optional<Message> sent = messageRepository.findByInquiryIdAndClientMsgId(inquiry.getId(),
                request.clientMsgId());
        if (sent.isPresent()) {
            if (sent.get().getSender() != MessageSender.CUSTOMER) {
                throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                        List.of(new FieldErrorResponse("clientMsgId", "이미 쓰인 메시지 ID예요. 새 ID로 보내 주세요")));
            }
            return new SentMessage(SendMessageResponse.of(sent.get(), aiStatus(inquiry)), false);
        }
        inquiryAccess.checkWritable(inquiry);
        List<Photo> photos = attachablePhotos(inquiry, photoIds);

        Message message = Message.builder()
                .inquiry(inquiry)
                .sender(MessageSender.CUSTOMER)
                .content(request.content())
                .clientMsgId(request.clientMsgId())
                .build();
        message.markSent();
        messageRepository.save(message);
        photos.forEach(photo -> photo.attachTo(message));
        // AI 분석 요청은 이 순번을 입력 버전으로 씀 - 커밋된 뒤에 시작 (AiAnalysisQueue)
        inquiryRepository.incrementAiInputSeq(inquiry.getId());
        AiStatus aiStatus = aiStatus(inquiry);
        if (aiStatus == AiStatus.PROCESSING) {
            eventPublisher.publishEvent(new AiAnalysisRequested(inquiry.getId()));
        }
        AccessLog.record(principal, "UPDATE", "inquiry.messages", inquiryId);
        return new SentMessage(SendMessageResponse.of(message, aiStatus), true);
    }

    // 사진 동의가 있고, 이 상담에서 업로드를 마쳤고, 아직 보내지 않은 사진만
    private List<Photo> attachablePhotos(Inquiry inquiry, List<Long> photoIds) {
        if (photoIds.isEmpty()) {
            return List.of();
        }
        inquiryAccess.requireConsent(inquiry, ConsentType.PHOTO);
        List<Photo> photos = photoRepository.findByInquiryIdAndIdIn(inquiry.getId(), photoIds);
        if (photos.size() != photoIds.size() || !photos.stream().allMatch(Photo::isAttachable)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    List.of(new FieldErrorResponse("photoIds", "업로드를 마친, 아직 보내지 않은 사진만 보낼 수 있어요")));
        }
        return photos;
    }

    // AI가 답하는 상담만 PROCESSING - 사장님 응대 중이거나 사장님 확인·예약·서비스 불가 단계면 NONE
    private static AiStatus aiStatus(Inquiry inquiry) {
        return inquiry.isAiAnswering() ? AiStatus.PROCESSING : AiStatus.NONE;
    }
}
