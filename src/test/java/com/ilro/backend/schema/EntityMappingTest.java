package com.ilro.backend.schema;

import com.ilro.backend.domain.admin.entity.Admin;
import com.ilro.backend.domain.ai.entity.AiAction;
import com.ilro.backend.domain.ai.entity.AiDecision;
import com.ilro.backend.domain.ai.entity.AiDecisionStatus;
import com.ilro.backend.domain.ai.entity.AiStage;
import com.ilro.backend.domain.ai.entity.DecidedBy;
import com.ilro.backend.domain.auth.entity.AuthToken;
import com.ilro.backend.domain.auth.entity.AuthTokenScope;
import com.ilro.backend.domain.auth.entity.AuthTokenType;
import com.ilro.backend.domain.auth.entity.PhoneVerification;
import com.ilro.backend.domain.auth.entity.VerificationPurpose;
import com.ilro.backend.domain.auth.entity.VerificationSendStatus;
import com.ilro.backend.domain.boiler.entity.BoilerErrorCode;
import com.ilro.backend.domain.customer.entity.Customer;
import com.ilro.backend.domain.inquiry.entity.Consent;
import com.ilro.backend.domain.inquiry.entity.ConsentType;
import com.ilro.backend.domain.inquiry.entity.HandoffRequest;
import com.ilro.backend.domain.inquiry.entity.HandoffRequestType;
import com.ilro.backend.domain.inquiry.entity.HandoffStatus;
import com.ilro.backend.domain.inquiry.entity.HandlerMode;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.entity.InquiryField;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldSource;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldStatus;
import com.ilro.backend.domain.inquiry.entity.InquiryStatus;
import com.ilro.backend.domain.inquiry.entity.Message;
import com.ilro.backend.domain.inquiry.entity.MessageDeliveryStatus;
import com.ilro.backend.domain.inquiry.entity.MessageSender;
import com.ilro.backend.domain.inquiry.entity.Photo;
import com.ilro.backend.domain.inquiry.entity.PhotoUploadStatus;
import com.ilro.backend.domain.notification.entity.Notification;
import com.ilro.backend.domain.notification.entity.NotificationChannel;
import com.ilro.backend.domain.notification.entity.NotificationEventType;
import com.ilro.backend.domain.notification.entity.NotificationRecipient;
import com.ilro.backend.domain.notification.entity.NotificationRefType;
import com.ilro.backend.domain.notification.entity.NotificationSendStatus;
import com.ilro.backend.domain.notification.entity.PushSubscription;
import com.ilro.backend.domain.owner.entity.ApprovalStatus;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.entity.OwnerBlock;
import com.ilro.backend.domain.owner.entity.OwnerBusinessHour;
import com.ilro.backend.domain.owner.entity.OwnerServiceArea;
import com.ilro.backend.domain.owner.entity.OwnerStatus;
import com.ilro.backend.domain.owner.entity.OwnerWorkType;
import com.ilro.backend.domain.owner.entity.WorkCategory;
import com.ilro.backend.domain.reservation.entity.ChangedBy;
import com.ilro.backend.domain.reservation.entity.RequestedBy;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationChange;
import com.ilro.backend.domain.reservation.entity.ReservationChangeCloseReason;
import com.ilro.backend.domain.reservation.entity.ReservationChangeStatus;
import com.ilro.backend.domain.reservation.entity.ReservationChangeType;
import com.ilro.backend.domain.reservation.entity.ReservationHistory;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.domain.reservation.entity.WorkNote;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// Entity로 저장·조회했을 때 마이그레이션(V1·V2) 테이블과 맞는지 확인 (ddl-auto: validate는 칸 이름·타입만 검사)
@SpringBootTest
@Transactional
class EntityMappingTest {

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("모든 테이블에 Entity가 하나씩 있다")
    void everyTableHasEntity() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE' "
                        + "AND table_name <> 'flyway_schema_history'",
                String.class);

        List<String> entityTables = em.getMetamodel().getEntities().stream()
                .map(entity -> entity.getJavaType().getAnnotation(Table.class).name())
                .collect(Collectors.toList());

        assertThat(entityTables).hasSize(23).containsExactlyInAnyOrderElementsOf(tables);
    }

    @Test
    @DisplayName("23개 Entity를 저장하면 DB 제약을 통과하고, 다시 읽으면 기본값·enum·JSON·요일이 그대로 나온다")
    void saveAndLoadAllEntities() {
        Instant start = Instant.parse("2099-01-05T01:00:00Z");
        Instant end = start.plus(1, ChronoUnit.HOURS);

        Admin admin = persist(Admin.builder().name("운영자").phone(unique("a")).build());
        Owner owner = persist(Owner.builder()
                .businessName("일로보일러").ownerName("김사장").phone(unique("o")).linkToken(unique("link")).build());
        OwnerBusinessHour monday = persist(OwnerBusinessHour.builder()
                .owner(owner).dayOfWeek(DayOfWeek.MONDAY).openTime(LocalTime.of(9, 0)).closeTime(LocalTime.of(18, 0))
                .build());
        persist(OwnerBusinessHour.builder().owner(owner).dayOfWeek(DayOfWeek.SUNDAY).isClosed(true).build());
        persist(OwnerServiceArea.builder().owner(owner).regionCode("41281").regionName("고양시 덕양구").build());
        OwnerWorkType repair = persist(OwnerWorkType.builder()
                .owner(owner).workCategory(WorkCategory.REPAIR).durationMinutes(60).build());
        persist(OwnerBlock.builder().owner(owner).startAt(start.minus(3, ChronoUnit.HOURS)).endAt(start).reason("개인 일정")
                .build());
        BoilerErrorCode errorCode = persist(BoilerErrorCode.builder()
                .manufacturer("경동나비엔").code(unique("E")).meaning("점화 불량").workCategory(WorkCategory.REPAIR).build());

        Customer customer = persist(Customer.builder().owner(owner).name("박고객").phone(unique("c")).build());
        Inquiry inquiry = persist(Inquiry.builder().owner(owner).customer(customer).build());
        Consent consent = persist(Consent.builder()
                .inquiry(inquiry).consentType(ConsentType.PRIVACY_REQUIRED).termsVersion("v1").build());
        Message question = persist(Message.builder()
                .inquiry(inquiry).sender(MessageSender.CUSTOMER).content("보일러가 안 켜져요").clientMsgId(unique("m"))
                .build());
        Photo photo = persist(Photo.builder()
                .inquiry(inquiry).message(question).s3KeyOriginal("inquiry/1/original.jpg").mimeType("image/jpeg")
                .sizeBytes(1024).build());
        AiDecision decision = persist(AiDecision.builder()
                .inquiry(inquiry).message(question).stage(AiStage.DECIDE).modelName("claude-haiku-4-5")
                .inputSnapshot("{\"turn\": 1}").outputRaw("{\"action\": \"ASK_MORE_INFO\"}")
                .confidence(new BigDecimal("0.85")).action(AiAction.ASK_MORE_INFO).decidedBy(DecidedBy.EXTERNAL_API)
                .status(AiDecisionStatus.SUCCESS).inputVersion(0L).requestId(unique("req")).build());
        AiDecision staleDecision = persist(AiDecision.builder()
                .inquiry(inquiry).message(question).stage(AiStage.DECIDE).decidedBy(DecidedBy.EXTERNAL_API)
                .status(AiDecisionStatus.STALE).inputVersion(0L).requestId(unique("req")).build());
        persist(Message.builder()
                .inquiry(inquiry).sender(MessageSender.AI).aiDecision(decision).content("모델명을 알려 주세요").build());
        persist(InquiryField.builder()
                .inquiry(inquiry).fieldKey("manufacturer").fieldValue("경동나비엔").source(InquiryFieldSource.PHOTO)
                .status(InquiryFieldStatus.CONFIRMED).photo(photo).aiDecision(decision).build());
        InquiryField symptomField = persist(InquiryField.builder()
                .inquiry(inquiry).fieldKey("symptom").fieldValue("안 켜짐").source(InquiryFieldSource.CUSTOMER)
                .status(InquiryFieldStatus.CONFIRMED).aiDecision(decision).message(question).build());
        HandoffRequest handoff = persist(HandoffRequest.builder()
                .inquiry(inquiry).requestType(HandoffRequestType.CALLBACK).name("박고객").phone("010-1234-5678").build());

        Reservation reservation = persist(Reservation.builder()
                .inquiry(inquiry).owner(owner).customer(customer).startAt(start).endAt(end).workType(repair)
                .travelTimeMinutes(20).workMinutes(60).bufferMinutes(15)
                .idempotencyKey(unique("idem")).idempotencyHash("a".repeat(64)).build());
        Instant proposalExpiry = start.minus(2, ChronoUnit.HOURS);
        ReservationChange change = persist(ReservationChange.builder()
                .reservation(reservation).changeType(ReservationChangeType.CHANGE).requestedBy(RequestedBy.CUSTOMER)
                .requestNote("다음 주 오전").prevStartAt(start).prevEndAt(end).expiresAt(proposalExpiry).baseVersion(0)
                .build());
        persist(ReservationHistory.builder()
                .reservation(reservation).toStatus(ReservationStatus.REQUESTED).changedBy(ChangedBy.SYSTEM).build());
        persist(WorkNote.builder().reservation(reservation).content("배기통 점검 필요").build());

        Notification notification = persist(Notification.builder()
                .owner(owner).recipient(NotificationRecipient.OWNER).eventType(NotificationEventType.NEW_RESERVATION)
                .refType(NotificationRefType.RESERVATION).refId(reservation.getId()).channel(NotificationChannel.IN_APP)
                .dedupeKey(unique("dedupe")).build());
        persist(PushSubscription.builder()
                .owner(owner).endpoint("https://push.example.com/" + unique("ep")).p256dh("key").auth("secret").build());
        PhoneVerification verification = persist(PhoneVerification.builder()
                .phone(owner.getPhone()).purpose(VerificationPurpose.OWNER_LOGIN).codeHash("hash")
                .sendStatus(VerificationSendStatus.SENT).expiresAt(Instant.now().plus(3, ChronoUnit.MINUTES))
                .targetOwner(owner).requestIp("203.0.113.7").build());
        PhoneVerification customerVerification = persist(PhoneVerification.builder()
                .phone(customer.getPhone()).purpose(VerificationPurpose.CUSTOMER_VERIFY).codeHash("hash")
                .sendStatus(VerificationSendStatus.SENT).expiresAt(Instant.now().plus(3, ChronoUnit.MINUTES))
                .targetInquiry(inquiry).requestIp("2001:db8::1").build());
        Instant tokenExpiry = Instant.now().plus(1, ChronoUnit.DAYS);
        AuthToken ownerToken = persist(AuthToken.ownerToken(unique("own"), owner, tokenExpiry));
        persist(AuthToken.adminToken(unique("adm"), admin, tokenExpiry));
        AuthToken inquiryToken = persist(AuthToken.inquiryToken(unique("inq"), inquiry, tokenExpiry));
        AuthToken customerToken = persist(AuthToken.customerToken(
                unique("cus"), owner, customer.getPhone(), AuthTokenScope.RESERVATION_LOOKUP, tokenExpiry));

        em.flush();
        // V2 상태값은 아직 바꾸는 서비스가 없어 SQL로 넣고 Entity로 읽음
        jdbc.update("UPDATE reservation_change SET status = 'CLOSED', close_reason = 'OWNER_CLOSED', "
                + "resolved_by = 'OWNER', resolved_at = CURRENT_TIMESTAMP WHERE id = ?", change.getId());
        jdbc.update("UPDATE notification SET send_status = 'SKIPPED', event_type = 'RESERVATION_CONFIRMED' "
                + "WHERE id = ?", notification.getId());
        em.clear();

        Owner foundOwner = em.find(Owner.class, owner.getId());
        assertThat(foundOwner.getStatus()).isEqualTo(OwnerStatus.READY);
        assertThat(foundOwner.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(foundOwner.getBufferMinutes()).isEqualTo(15);
        assertThat(foundOwner.getCreatedAt()).isNotNull();
        assertThat(foundOwner.getUpdatedAt()).isNotNull();

        assertThat(em.find(OwnerBusinessHour.class, monday.getId()).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(jdbc.queryForObject("SELECT day_of_week FROM owner_business_hour WHERE id = ?", Integer.class,
                monday.getId())).isEqualTo(1);

        Inquiry foundInquiry = em.find(Inquiry.class, inquiry.getId());
        assertThat(foundInquiry.getStatus()).isEqualTo(InquiryStatus.COLLECTING);
        assertThat(foundInquiry.getHandlerMode()).isEqualTo(HandlerMode.AI);
        assertThat(foundInquiry.getVersion()).isZero();
        assertThat(foundInquiry.getAiInputSeq()).isZero();

        AiDecision foundDecision = em.find(AiDecision.class, decision.getId());
        assertThat(foundDecision.getInputSnapshot()).isEqualTo("{\"turn\": 1}");
        assertThat(foundDecision.getConfidence()).isEqualByComparingTo("0.85");
        assertThat(foundDecision.getInputVersion()).isZero();
        assertThat(foundDecision.getRequestId()).startsWith("req-");
        AiDecision foundStale = em.find(AiDecision.class, staleDecision.getId());
        assertThat(foundStale.getStatus()).isEqualTo(AiDecisionStatus.STALE);
        assertThat(foundStale.getAction()).isNull();
        assertThat(jdbc.queryForObject("SELECT output_raw ->> 'action' FROM ai_decision WHERE id = ?", String.class,
                decision.getId())).isEqualTo("ASK_MORE_INFO");

        assertThat(em.find(Message.class, question.getId()).getDeliveryStatus()).isEqualTo(MessageDeliveryStatus.PENDING);
        assertThat(em.find(Photo.class, photo.getId()).getUploadStatus()).isEqualTo(PhotoUploadStatus.UPLOADING);
        assertThat(em.find(Consent.class, consent.getId()).getAgreedAt()).isNotNull();
        assertThat(em.find(HandoffRequest.class, handoff.getId()).getStatus()).isEqualTo(HandoffStatus.WAITING);
        assertThat(em.find(BoilerErrorCode.class, errorCode.getId()).getWorkCategory()).isEqualTo(WorkCategory.REPAIR);
        OwnerWorkType foundRepair = em.find(OwnerWorkType.class, repair.getId());
        assertThat(foundRepair.isActive()).isTrue();
        assertThat(foundRepair.isAutoBookingAllowed()).isFalse();
        assertThat(em.find(InquiryField.class, symptomField.getId()).getMessage().getId()).isEqualTo(question.getId());

        Reservation foundReservation = em.find(Reservation.class, reservation.getId());
        assertThat(foundReservation.getStatus()).isEqualTo(ReservationStatus.REQUESTED);
        assertThat(foundReservation.getVersion()).isZero();
        assertThat(foundReservation.getStartAt()).isEqualTo(start);
        assertThat(foundReservation.getIdempotencyHash()).hasSize(64);
        ReservationChange foundChange = em.find(ReservationChange.class, change.getId());
        assertThat(foundChange.getStatus()).isEqualTo(ReservationChangeStatus.CLOSED);
        assertThat(foundChange.getCloseReason()).isEqualTo(ReservationChangeCloseReason.OWNER_CLOSED);
        assertThat(foundChange.getResolvedBy()).isEqualTo(ChangedBy.OWNER);
        assertThat(foundChange.getExpiresAt()).isEqualTo(proposalExpiry);

        Notification foundNotification = em.find(Notification.class, notification.getId());
        assertThat(foundNotification.getSendStatus()).isEqualTo(NotificationSendStatus.SKIPPED);
        assertThat(foundNotification.getEventType()).isEqualTo(NotificationEventType.RESERVATION_CONFIRMED);

        PhoneVerification foundVerification = em.find(PhoneVerification.class, verification.getId());
        assertThat(foundVerification.getAttemptCount()).isZero();
        assertThat(foundVerification.getTargetOwner().getId()).isEqualTo(owner.getId());
        assertThat(foundVerification.getRequestIp()).isEqualTo("203.0.113.7");
        assertThat(em.find(PhoneVerification.class, customerVerification.getId()).getTargetInquiry().getId())
                .isEqualTo(inquiry.getId());
        assertThat(em.find(Admin.class, admin.getId()).getRole().name()).isEqualTo("STAFF");

        AuthToken foundOwnerToken = em.find(AuthToken.class, ownerToken.getId());
        assertThat(foundOwnerToken.getTokenType()).isEqualTo(AuthTokenType.OWNER);
        assertThat(foundOwnerToken.getOwner().getId()).isEqualTo(owner.getId());
        assertThat(foundOwnerToken.getCreatedAt()).isNotNull();
        assertThat(foundOwnerToken.getRevokedAt()).isNull();
        assertThat(em.find(AuthToken.class, inquiryToken.getId()).getInquiry().getId()).isEqualTo(inquiry.getId());
        AuthToken foundCustomerToken = em.find(AuthToken.class, customerToken.getId());
        assertThat(foundCustomerToken.getScope()).isEqualTo(AuthTokenScope.RESERVATION_LOOKUP);
        assertThat(foundCustomerToken.getCustomerPhone()).isEqualTo(customer.getPhone());
    }

    private <T> T persist(T entity) {
        em.persist(entity);
        return entity;
    }

    // UNIQUE 칸이 로컬 DB에 남아 있는 데이터와 겹치지 않게 임의 값 사용
    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
