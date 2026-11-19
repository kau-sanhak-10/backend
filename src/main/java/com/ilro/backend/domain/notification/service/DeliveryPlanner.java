package com.ilro.backend.domain.notification.service;

import com.ilro.backend.domain.inquiry.repository.InquiryRepository;
import com.ilro.backend.domain.notification.entity.Notification;
import com.ilro.backend.domain.notification.entity.NotificationChannel;
import com.ilro.backend.domain.notification.entity.NotificationEventType;
import com.ilro.backend.domain.notification.entity.NotificationRecipient;
import com.ilro.backend.domain.notification.entity.NotificationRefType;
import com.ilro.backend.domain.notification.entity.NotifySettingType;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.entity.OwnerStatus;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationChange;
import com.ilro.backend.domain.reservation.entity.ReservationChangeStatus;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.domain.reservation.repository.ReservationChangeRepository;
import com.ilro.backend.domain.reservation.repository.ReservationRepository;
import com.ilro.backend.global.common.util.KstTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

// 알림 한 건을 보낼지·무엇을 보낼지 정함 (명세 5.10, BE-14) - 발송기가 보내기 직전, 읽기 트랜잭션 안에서 부름
// 보낼 필요가 없어졌으면 Skip (SKIPPED로 남음):
//   탈퇴한 업체 / 취소된 예약의 확정 알림 / 끝난 변경 제안·요청 / 삭제된 문의 / 사장님이 꺼 둔 유형
// 고객 문자: 예약에 저장된 고객 번호로, 업체명·결과 종류·방문일시·확인 링크만 (주소·증상·사진·메모 X)
//   링크는 업체 접수 링크 - 열어도 번호 인증(RESERVATION_LOOKUP)을 해야 예약이 보임
// 사장님 푸시: 내용 없는 푸시라 받는 사람(업체)만 정함
@Component
public class DeliveryPlanner {

    // 예: 11월 21일(금) 오후 2:00
    static final DateTimeFormatter VISIT_TIME =
            DateTimeFormatter.ofPattern("M월 d일(E) a h:mm", Locale.KOREAN).withZone(KstTime.ZONE);

    // 고객 접수 화면 경로 (IntakeLinkService와 같은 주소)
    private static final String LINK_PATH = "/customer/";

    private final ReservationRepository reservationRepository;
    private final ReservationChangeRepository changeRepository;
    private final InquiryRepository inquiryRepository;
    private final NotificationSettingsService settingsService;
    private final String intakeBaseUrl;

    public DeliveryPlanner(ReservationRepository reservationRepository, ReservationChangeRepository changeRepository,
                           InquiryRepository inquiryRepository, NotificationSettingsService settingsService,
                           @Value("${app.intake.base-url}") String intakeBaseUrl) {
        this.reservationRepository = reservationRepository;
        this.changeRepository = changeRepository;
        this.inquiryRepository = inquiryRepository;
        this.settingsService = settingsService;
        this.intakeBaseUrl = intakeBaseUrl;
    }

    // 발송기가 받는 결정 - 엔티티 대신 보낼 값만 담음 (트랜잭션 밖에서 보내므로)
    public sealed interface Plan permits Skip, Sms, Push {
    }

    public record Skip(String reason) implements Plan {
    }

    public record Sms(String phone, String text) implements Plan {
    }

    public record Push(Long ownerId) implements Plan {
    }

    public Plan plan(Notification notification, Instant now) {
        Owner owner = notification.getOwner();
        if (owner.getStatus() == OwnerStatus.WITHDRAWN) {
            return new Skip("owner withdrawn");
        }
        if (notification.getRecipient() == NotificationRecipient.CUSTOMER
                && notification.getChannel() == NotificationChannel.SMS) {
            return customerSms(notification, owner, now);
        }
        if (notification.getRecipient() == NotificationRecipient.OWNER
                && notification.getChannel() == NotificationChannel.WEB_PUSH) {
            return ownerPush(notification, owner, now);
        }
        return new Skip("unsupported " + notification.getRecipient() + "/" + notification.getChannel());
    }

    // ---------- 고객 문자 ----------

    private Plan customerSms(Notification notification, Owner owner, Instant now) {
        if (notification.getRefType() != NotificationRefType.RESERVATION) {
            return new Skip("customer sms without reservation");
        }
        Optional<Reservation> found = reservationRepository.findById(notification.getRefId());
        if (found.isEmpty() || found.get().getCustomer() == null) {
            return new Skip("reservation or customer missing");
        }
        Reservation reservation = found.get();
        ReservationChange change = changeOf(notification).orElse(null);
        String stale = staleReason(notification, reservation, change, now);
        if (stale != null) {
            return new Skip(stale);
        }
        String text = smsText(notification.getEventType(), owner, reservation, change);
        if (text == null) {
            return new Skip("no customer text for " + notification.getEventType());
        }
        return new Sms(reservation.getCustomer().getPhone(), text);
    }

    // 문안 - 앞에 [일로]와 업체명, 끝에 확인 링크
    private String smsText(NotificationEventType eventType, Owner owner, Reservation reservation,
                           ReservationChange change) {
        String head = "[일로] " + owner.getBusinessName() + " ";
        String link = "\n확인: " + intakeBaseUrl + LINK_PATH + owner.getLinkToken();
        return switch (eventType) {
            case RESERVATION_CONFIRMED, NEW_RESERVATION -> head + "방문 예약이 확정됐어요.\n방문: "
                    + VISIT_TIME.format(reservation.getStartAt()) + link;
            case CHANGE_PROPOSED -> change == null || change.getProposedStartAt() == null ? null
                    : head + "방문 시간 변경을 제안했어요. 아래에서 확인하고 답해 주세요.\n제안: "
                    + VISIT_TIME.format(change.getProposedStartAt()) + link;
            case CHANGE_APPLIED -> head + "방문 시간이 변경됐어요.\n방문: "
                    + VISIT_TIME.format(reservation.getStartAt()) + link;
            case RESERVATION_CANCELED, CANCEL -> head + "방문 예약이 취소됐어요." + link;
            default -> null;
        };
    }

    // ---------- 사장님 푸시 ----------

    private Plan ownerPush(Notification notification, Owner owner, Instant now) {
        NotifySettingType setting = settingOf(notification.getEventType());
        if (setting != null && !settingsService.settingsOf(owner).get(setting)) {
            return new Skip("owner turned off " + setting);
        }
        if (notification.getRefType() == NotificationRefType.INQUIRY) {
            boolean alive = inquiryRepository.findById(notification.getRefId())
                    .filter(inquiry -> !inquiry.isDeleted())
                    .isPresent();
            return alive ? new Push(owner.getId()) : new Skip("inquiry deleted");
        }
        Optional<Reservation> reservation = reservationRepository.findById(notification.getRefId());
        if (reservation.isEmpty()) {
            return new Skip("reservation missing");
        }
        String stale = staleReason(notification, reservation.get(), changeOf(notification).orElse(null), now);
        return stale == null ? new Push(owner.getId()) : new Skip(stale);
    }

    // 사건 → 사장님 수신 설정 유형 (명세 5.6) - 설정에 없는 사건이면 null (항상 보냄)
    static NotifySettingType settingOf(NotificationEventType eventType) {
        return switch (eventType) {
            case RESERVATION_CONFIRMED, NEW_RESERVATION -> NotifySettingType.NEW_RESERVATION;
            case NEEDS_CHECK -> NotifySettingType.NEEDS_CHECK;
            case CHANGE, CHANGE_PROPOSED, CHANGE_APPLIED -> NotifySettingType.CHANGE;
            case RESERVATION_CANCELED, CANCEL -> NotifySettingType.CANCEL;
            case RESULT -> null;
        };
    }

    // ---------- 최신 상태 확인 ----------

    // 보내기 직전에 보니 이미 지난 일이면 그 이유 (명세 5.10) - 보내도 되면 null
    private static String staleReason(Notification notification, Reservation reservation, ReservationChange change,
                                      Instant now) {
        return switch (notification.getEventType()) {
            // 확정 알림인데 그사이 취소·완료됨
            case RESERVATION_CONFIRMED, NEW_RESERVATION -> reservation.getStatus() == ReservationStatus.CONFIRMED
                    ? null : "reservation " + reservation.getStatus();
            // 제안 알림인데 고객이 이미 답했거나 기한이 지났거나 예약이 취소됨
            case CHANGE_PROPOSED -> change != null && change.getStatus() == ReservationChangeStatus.PROPOSED
                    && !change.isExpiredProposal(now) && reservation.getStatus() == ReservationStatus.CONFIRMED
                    ? null : "proposal no longer open";
            // 변경 요청 알림인데 사장님이 이미 처리했거나 닫힘
            case CHANGE -> change == null || change.getStatus() == ReservationChangeStatus.REQUESTED
                    ? null : "change request " + change.getStatus();
            // 변경 확정·취소는 일어난 사실이라 그대로 알림
            default -> null;
        };
    }

    // 변경 사건의 변경 ID - 중복 키 RESERVATION:{예약ID}:{사건}:{변경ID}:{수신자}:{채널} (ReservationNotifier)
    private Optional<ReservationChange> changeOf(Notification notification) {
        String[] parts = notification.getDedupeKey().split(":");
        if (parts.length < 6 || !parts[3].chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        return changeRepository.findById(Long.parseLong(parts[3]));
    }
}