package com.ilro.backend.domain.inquiry.service;

import com.ilro.backend.domain.ai.entity.AiDecision;
import com.ilro.backend.domain.ai.entity.AiDecisionStatus;
import com.ilro.backend.domain.ai.entity.AiStage;
import com.ilro.backend.domain.ai.repository.AiDecisionRepository;
import com.ilro.backend.domain.inquiry.dto.OwnerHomeResponse;
import com.ilro.backend.domain.inquiry.dto.OwnerHomeResponse.TodayVisit;
import com.ilro.backend.domain.inquiry.dto.OwnerInquiryDetailResponse;
import com.ilro.backend.domain.inquiry.dto.OwnerInquiryDetailResponse.AiSummary;
import com.ilro.backend.domain.inquiry.dto.OwnerInquiryListResponse;
import com.ilro.backend.domain.inquiry.dto.OwnerUpdateInquiryRequest;
import com.ilro.backend.domain.inquiry.dto.OwnerUpdateInquiryRequest.OwnerVisitInfo;
import com.ilro.backend.domain.inquiry.entity.Consent;
import com.ilro.backend.domain.inquiry.entity.ConsentType;
import com.ilro.backend.domain.inquiry.entity.HandlerMode;
import com.ilro.backend.domain.inquiry.entity.HandoffRequest;
import com.ilro.backend.domain.inquiry.entity.HandoffStatus;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldKey;
import com.ilro.backend.domain.inquiry.entity.InquiryStatus;
import com.ilro.backend.domain.inquiry.entity.Message;
import com.ilro.backend.domain.inquiry.entity.PhotoUploadStatus;
import com.ilro.backend.domain.inquiry.repository.ConsentRepository;
import com.ilro.backend.domain.inquiry.repository.HandoffRequestRepository;
import com.ilro.backend.domain.inquiry.repository.InquiryFieldRepository;
import com.ilro.backend.domain.inquiry.repository.InquiryRepository;
import com.ilro.backend.domain.inquiry.repository.MessageRepository;
import com.ilro.backend.domain.inquiry.repository.PhotoRepository;
import com.ilro.backend.domain.notification.entity.NotificationChannel;
import com.ilro.backend.domain.notification.entity.NotificationRecipient;
import com.ilro.backend.domain.notification.repository.NotificationRepository;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.domain.reservation.repository.ReservationRepository;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.common.util.AccessLog;
import com.ilro.backend.global.common.util.KstTime;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

// 사장님 홈·문의 목록·상세·수정 (B-10·B-12·B-13·B-14, 명세 4.5)
// 자기 업체 문의만 - 다른 업체·삭제된 문의는 404
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerInquiryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final InquiryAccess inquiryAccess;
    private final InquiryFieldService fieldService;
    private final VisitInfoWriter visitInfoWriter;
    private final InquiryRepository inquiryRepository;
    private final InquiryFieldRepository fieldRepository;
    private final MessageRepository messageRepository;
    private final PhotoRepository photoRepository;
    private final HandoffRequestRepository handoffRepository;
    private final ConsentRepository consentRepository;
    private final AiDecisionRepository aiDecisionRepository;
    private final ReservationRepository reservationRepository;
    private final NotificationRepository notificationRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public OwnerHomeResponse getHome(AuthPrincipal principal) {
        Long ownerId = principal.ownerId();
        LocalDate today = LocalDate.now(clock.withZone(KstTime.ZONE));
        List<TodayVisit> visits = reservationRepository.findStartingBetween(ownerId,
                        today.atStartOfDay(KstTime.ZONE).toInstant(),
                        today.plusDays(1).atStartOfDay(KstTime.ZONE).toInstant(), ReservationStatus.CANCELED)
                .stream()
                .map(TodayVisit::from)
                .toList();
        // 오늘 방문에 고객 이름·주소가 담겨 있으므로 보여 줄 때 기록
        if (!visits.isEmpty()) {
            AccessLog.record(principal, "VIEW", "owner.home", ownerId);
        }
        return new OwnerHomeResponse(today,
                inquiryRepository.countByOwnerIdAndStatusAndDeletedAtIsNull(ownerId, InquiryStatus.OWNER_CHECK),
                handoffRepository.countByOwner(ownerId, HandoffStatus.WAITING),
                notificationRepository.countByOwnerIdAndRecipientAndChannelAndReadAtIsNull(ownerId,
                        NotificationRecipient.OWNER, NotificationChannel.IN_APP),
                visits);
    }

    // status·handlerMode는 보낸 것만 거름, page는 0부터
    public OwnerInquiryListResponse getInquiries(AuthPrincipal principal, InquiryStatus status,
                                                 HandlerMode handlerMode, Integer page, Integer size) {
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        List<FieldErrorResponse> errors = new ArrayList<>();
        if (pageNumber < 0 || (long) pageNumber * pageSize > Integer.MAX_VALUE) {
            errors.add(new FieldErrorResponse("page", "0 이상의 쪽 번호를 보내 주세요"));
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            errors.add(new FieldErrorResponse("size", "1 ~ " + MAX_PAGE_SIZE + " 사이여야 합니다"));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, errors);
        }

        Page<Inquiry> found = inquiryRepository.findOwnerInquiries(principal.ownerId(), status, handlerMode,
                PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Direction.DESC, "id")));
        List<Long> ids = found.getContent().stream().map(Inquiry::getId).toList();
        Map<Long, Message> lastMessages = ids.isEmpty() ? Map.of() : messageRepository.findLatestByInquiryIdIn(ids)
                .stream()
                .collect(Collectors.toMap(message -> message.getInquiry().getId(), Function.identity()));
        Map<Long, HandoffRequest> handoffs = ids.isEmpty() ? Map.of() : handoffRepository
                .findByInquiryIdInAndStatusIn(ids, HandoffRequest.ACTIVE).stream()
                .collect(Collectors.toMap(handoff -> handoff.getInquiry().getId(), Function.identity()));
        Set<Long> requiredWaitingOwner = fieldService.findRequiredWaitingOwner(ids);
        // 고객 이름·번호가 담긴 목록이므로 보여 줄 때 기록
        if (!ids.isEmpty()) {
            AccessLog.record(principal, "VIEW", "inquiry.list", principal.ownerId());
        }
        return new OwnerInquiryListResponse(found.getContent().stream()
                .map(inquiry -> OwnerInquiryListResponse.Item.of(inquiry, handoffs.get(inquiry.getId()),
                        lastMessages.get(inquiry.getId()), requiredWaitingOwner.contains(inquiry.getId())))
                .toList(), found.getNumber(), found.getSize(), found.getTotalElements(), found.getTotalPages(),
                found.isLast());
    }

    public OwnerInquiryDetailResponse getInquiry(AuthPrincipal principal, Long inquiryId) {
        Inquiry inquiry = inquiryAccess.findOwned(principal, inquiryId);
        OwnerInquiryDetailResponse response = detail(inquiry);
        AccessLog.record(principal, "VIEW", "inquiry", inquiryId);
        return response;
    }

    // 방문정보·접수 항목 중 보낸 것만 - 하나라도 바뀌면 version·AI 입력 순번을 올림
    // 종료된 문의는 고치지 않음 (409), 예약에 영향을 주는 방문정보는 예약 변경 절차로 (422)
    @Transactional
    public OwnerInquiryDetailResponse updateInquiry(AuthPrincipal principal, Long inquiryId,
                                                    OwnerUpdateInquiryRequest request) {
        OwnerVisitInfo visitInfo = request.visitInfo();
        if (visitInfo == null && request.fields() == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    List.of(new FieldErrorResponse("visitInfo", "방문정보나 접수 항목 중 하나는 보내 주세요")));
        }
        String phone = visitInfo == null ? null : VisitInfoWriter.mobilePhone(visitInfo.phone(), "visitInfo.phone");
        Map<InquiryFieldKey, String> values = request.fields() == null ? Map.of()
                : InquiryFieldService.toOwnerValues(request.fields());

        Inquiry inquiry = inquiryAccess.lockOwned(principal, inquiryId);
        inquiryAccess.checkOpen(inquiry);
        inquiryAccess.checkVersion(inquiry, request.version());
        int versionBefore = inquiry.getVersion();
        boolean changed = false;
        if (visitInfo != null) {
            changed = visitInfoWriter.write(inquiry, new VisitInfoWriter.Input(visitInfo.name(), phone,
                    visitInfo.address(), visitInfo.addressDetail(), visitInfo.lat(), visitInfo.lng(),
                    visitInfo.accessNote(), visitInfo.workCategory(), "visitInfo."), true);
        }
        if (fieldService.writeOwnerValues(inquiry, values)) {
            changed = true;
        }
        if (changed) {
            inquiryAccess.markInfoChanged(inquiry, versionBefore);
        }
        // 바뀐 게 없어도 상세를 돌려주므로 조회로 기록
        AccessLog.record(principal, changed ? "UPDATE" : "VIEW", "inquiry", inquiryId);
        return detail(inquiry);
    }

    private OwnerInquiryDetailResponse detail(Inquiry inquiry) {
        Long inquiryId = inquiry.getId();
        AiDecision decision = aiDecisionRepository
                .findFirstByInquiryIdAndStageAndStatusOrderByIdDesc(inquiryId, AiStage.DECIDE, AiDecisionStatus.SUCCESS)
                .orElse(null);
        List<ConsentType> consents = consentRepository.findByInquiryIdOrderByIdAsc(inquiryId).stream()
                .map(Consent::getConsentType)
                .distinct()
                .toList();
        return OwnerInquiryDetailResponse.of(inquiry, fieldRepository.findAllOf(inquiryId),
                aiSummary(inquiry, decision),
                photoRepository.findByInquiryIdAndUploadStatusOrderByIdAsc(inquiryId, PhotoUploadStatus.DONE),
                handoffRepository.findByInquiryIdOrderByIdDesc(inquiryId),
                reservationRepository.findByInquiryIdAndStatusNot(inquiryId, ReservationStatus.CANCELED).orElse(null),
                consents, !fieldService.findRequiredWaitingOwner(List.of(inquiryId)).isEmpty());
    }

    // 해석은 적용된 마지막 AI 판단의 원본 응답(result)에서 꺼냄 - 사실(항목 값)과 섞지 않음
    private AiSummary aiSummary(Inquiry inquiry, AiDecision decision) {
        if (decision == null) {
            return inquiry.getAiSummary() == null ? null
                    : new AiSummary(inquiry.getAiSummary(), List.of(), List.of(), List.of(), null, null, null);
        }
        JsonNode result = MissingNode.getInstance();
        if (decision.getOutputRaw() != null) {
            try {
                result = jsonMapper.readTree(decision.getOutputRaw()).path("result");
            } catch (JacksonException e) {
                // 읽지 못한 기록은 해석 없이 보여 줌
            }
        }
        JsonNode reason = result.path("nextAction").path("reason");
        return new AiSummary(inquiry.getAiSummary(), nodes(result.path("interpretations")),
                nodes(result.path("conflicts")),
                nodes(result.path("safety").path("reasons")).stream()
                        .filter(JsonNode::isString)
                        .map(JsonNode::asString)
                        .toList(),
                decision.getAction(), reason.isString() ? reason.asString() : null,
                KstTime.of(decision.getCreatedAt()));
    }

    private static List<JsonNode> nodes(JsonNode array) {
        List<JsonNode> nodes = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(nodes::add);
        }
        return nodes;
    }
}
