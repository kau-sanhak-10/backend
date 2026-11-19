package com.ilro.backend.domain.notification;

import com.ilro.backend.domain.notification.service.NotificationDispatcher;
import com.ilro.backend.infra.push.WebPushResult;
import com.ilro.backend.support.CapturingSmsSender;
import com.ilro.backend.support.CapturingWebPushSender;
import com.ilro.backend.support.TestAccounts;
import com.ilro.backend.support.TestPushConfig;
import com.ilro.backend.support.TestSmsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 알림 발송기 (명세 5.10, BE-14) - 행은 SQL로 넣고 발송기를 직접 부름 (자동 실행 간격은 테스트 설정에서 1시간)
// 문자·푸시는 캡처 발송기가 받음 - 실제로 나가지 않음
@SpringBootTest
@Import({TestSmsConfig.class, TestPushConfig.class})
class NotificationDispatcherFlowTest {

    // 2026-11-27(금) 14:00 한국 시간 - 문안에 "11월 27일(금) 오후 2:00"으로 찍혀야 함
    private static final OffsetDateTime VISIT = OffsetDateTime.of(2026, 11, 27, 14, 0, 0, 0, ZoneOffset.ofHours(9));

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NotificationDispatcher dispatcher;

    @Autowired
    private CapturingSmsSender smsSender;

    @Autowired
    private CapturingWebPushSender pushSender;

    @Value("${app.intake.base-url}")
    private String intakeBaseUrl;

    private TestAccounts accounts;
    private long ownerId;
    private String phone;
    private long customerId;
    // 이 테스트에서 만든 예약 수 - 같은 업체 확정 예약은 시간이 겹치면 안 돼서(ex_reservation_confirmed_overlap) 하루씩 뒤로
    private int reservations;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(jdbc);
        ownerId = accounts.approvedOwner(accounts.newPhone());
        phone = accounts.newPhone();
        customerId = jdbc.queryForObject("INSERT INTO customer (owner_id, name, phone) VALUES (?, '김고객', ?) "
                + "RETURNING id", Long.class, ownerId, phone);
    }

    @AfterEach
    void tearDown() {
        accounts.cleanUp();
    }

    // ---------- 고객 문자 ----------

    @Test
    @DisplayName("확정 문자 - 업체명·결과·방문일시(한국 시간)·접수 링크만, 주소·이름은 안 넣음 → SENT·시도 1")
    void confirmedSms() {
        long reservation = reservation("CONFIRMED");
        long id = notification("RESERVATION_CONFIRMED", reservation, "CONFIRMED", "CUSTOMER", "SMS");

        dispatchAll();

        assertThat(row(id)).containsEntry("send_status", "SENT").containsEntry("attempt_count", 1);
        String text = smsSender.lastMessage(phone);
        assertThat(text).contains("[일로] 테스트보일러", "방문 예약이 확정됐어요", "11월 27일(금) 오후 2:00",
                intakeBaseUrl + "/customer/" + accounts.linkToken(ownerId));
        assertThat(text).doesNotContain("화정로", "김고객");
    }

    @Test
    @DisplayName("확정 문자 - 보내기 전에 예약이 취소됐으면 SKIPPED, 문자 안 나감")
    void skipsConfirmedWhenCanceled() {
        long reservation = reservation("CANCELED");
        long id = notification("RESERVATION_CONFIRMED", reservation, "CONFIRMED", "CUSTOMER", "SMS");

        dispatchAll();

        assertThat(row(id)).containsEntry("send_status", "SKIPPED").containsEntry("attempt_count", 0);
        assertThat(smsSender.hasSentTo(phone)).isFalse();
    }

    @Test
    @DisplayName("변경 제안 문자 - 기한이 지난 제안이면 SKIPPED, 기한 전이면 제안 시각과 함께 보냄")
    void proposal() {
        // 예약 하나에 진행 중인 변경은 하나만 (uq_reservation_change_active) - 경우마다 예약을 따로
        long expiredReservation = reservation("CONFIRMED");
        long expired = change(expiredReservation, "PROPOSED", "now() - interval '1 minute'");
        long expiredId = notification("CHANGE_PROPOSED", expiredReservation, "CHANGE_PROPOSED:" + expired,
                "CUSTOMER", "SMS");
        dispatchAll();
        assertThat(row(expiredId)).containsEntry("send_status", "SKIPPED");
        assertThat(smsSender.hasSentTo(phone)).isFalse();

        long openReservation = reservation("CONFIRMED");
        long open = change(openReservation, "PROPOSED", "now() + interval '1 day'");
        long openId = notification("CHANGE_PROPOSED", openReservation, "CHANGE_PROPOSED:" + open,
                "CUSTOMER", "SMS");
        dispatchAll();
        assertThat(row(openId)).containsEntry("send_status", "SENT");
        assertThat(smsSender.lastMessage(phone)).contains("방문 시간 변경을 제안했어요", "11월 28일(토) 오전 10:00");
    }

    @Test
    @DisplayName("재시도 - 실패하면 FAILED, 1분·5분 지나야 다시 보내고 3번 실패하면 더 안 보냄")
    void retriesWithBackoff() {
        smsSender.failFor(phone);
        long id = notification("RESERVATION_CONFIRMED", reservation("CONFIRMED"), "CONFIRMED", "CUSTOMER", "SMS");

        dispatchAll();
        assertThat(row(id)).containsEntry("send_status", "FAILED").containsEntry("attempt_count", 1);
        dispatchAll();
        assertThat(row(id)).containsEntry("attempt_count", 1);

        ageBy(id, "2 minutes");
        dispatchAll();
        assertThat(row(id)).containsEntry("attempt_count", 2);
        dispatchAll();
        assertThat(row(id)).containsEntry("attempt_count", 2);

        ageBy(id, "10 minutes");
        dispatchAll();
        assertThat(row(id)).containsEntry("send_status", "FAILED").containsEntry("attempt_count", 3);

        ageBy(id, "1 hour");
        dispatchAll();
        assertThat(row(id)).containsEntry("attempt_count", 3);
    }

    // ---------- 사장님 푸시 ----------

    @Test
    @DisplayName("푸시 - 사장님 기기 모두로, 없어진 구독(GONE)은 지우고 하나라도 받으면 SENT")
    void ownerPush() {
        String phoneDevice = endpoint();
        String goneDevice = endpoint();
        subscribe(phoneDevice);
        subscribe(goneDevice);
        pushSender.answer(goneDevice, WebPushResult.GONE);
        long id = notification("RESERVATION_CONFIRMED", reservation("CONFIRMED"), "CONFIRMED", "OWNER", "WEB_PUSH");

        dispatchAll();

        assertThat(row(id)).containsEntry("send_status", "SENT");
        assertThat(pushSender.hasSentTo(phoneDevice)).isTrue();
        assertThat(subscriptions(phoneDevice)).isEqualTo(1);
        assertThat(subscriptions(goneDevice)).isZero();
    }

    @Test
    @DisplayName("푸시 - 구독이 없으면(권한 거부·미지원) SKIPPED, 앱 안 알림은 PENDING 그대로 목록에 남음")
    void noSubscription() {
        long reservation = reservation("CONFIRMED");
        long push = notification("RESERVATION_CONFIRMED", reservation, "CONFIRMED", "OWNER", "WEB_PUSH");
        long inApp = notification("RESERVATION_CONFIRMED", reservation, "CONFIRMED", "OWNER", "IN_APP");

        dispatchAll();

        assertThat(row(push)).containsEntry("send_status", "SKIPPED");
        assertThat(row(inApp)).containsEntry("send_status", "PENDING").containsEntry("attempt_count", 0);
    }

    @Test
    @DisplayName("푸시 - 사장님이 그 유형을 꺼 두면 SKIPPED, 다른 유형은 보냄")
    void respectsSettings() {
        String device = endpoint();
        subscribe(device);
        jdbc.update("UPDATE owner SET notify_settings = CAST(? AS jsonb) WHERE id = ?",
                "{\"NEW_RESERVATION\":false,\"NEEDS_CHECK\":true,\"CHANGE\":true,\"CANCEL\":true}", ownerId);
        long reservation = reservation("CONFIRMED");
        long off = notification("RESERVATION_CONFIRMED", reservation, "CONFIRMED", "OWNER", "WEB_PUSH");
        long on = notification("RESERVATION_CANCELED", reservation, "CANCELED", "OWNER", "WEB_PUSH");

        dispatchAll();

        assertThat(row(off)).containsEntry("send_status", "SKIPPED");
        assertThat(row(on)).containsEntry("send_status", "SENT");
    }

    @Test
    @DisplayName("탈퇴한 업체 - 문자·푸시 모두 SKIPPED")
    void withdrawnOwner() {
        subscribe(endpoint());
        long reservation = reservation("CONFIRMED");
        long sms = notification("RESERVATION_CONFIRMED", reservation, "CONFIRMED", "CUSTOMER", "SMS");
        long push = notification("RESERVATION_CONFIRMED", reservation, "CONFIRMED", "OWNER", "WEB_PUSH");
        jdbc.update("UPDATE owner SET status = 'WITHDRAWN' WHERE id = ?", ownerId);

        dispatchAll();

        assertThat(row(sms)).containsEntry("send_status", "SKIPPED");
        assertThat(row(push)).containsEntry("send_status", "SKIPPED");
        assertThat(smsSender.hasSentTo(phone)).isFalse();
    }

    // ---------- 도우미 ----------

    // 다른 테스트·로컬 데이터의 알림이 먼저 골라질 수 있어 남은 게 없을 때까지 돌림
    private void dispatchAll() {
        for (int round = 0; round < 20 && dispatcher.dispatchDue() > 0; round++) {
            // 다음 묶음
        }
    }

    private long reservation(String status) {
        OffsetDateTime start = VISIT.plusDays(reservations++);
        return jdbc.queryForObject("INSERT INTO reservation (owner_id, customer_id, start_at, end_at, address, status) "
                        + "VALUES (?, ?, ?, ?, '경기 고양시 덕양구 화정로 1', ?) RETURNING id", Long.class,
                ownerId, customerId, start, start.plusHours(1), status);
    }

    // 제안 시각은 11/28(토) 10:00
    private long change(long reservation, String status, String expiresAtSql) {
        OffsetDateTime proposed = VISIT.plusDays(1).withHour(10);
        return jdbc.queryForObject("INSERT INTO reservation_change (reservation_id, change_type, requested_by, "
                        + "prev_start_at, prev_end_at, proposed_start_at, proposed_end_at, status, expires_at, base_version) "
                        + "VALUES (?, 'CHANGE', 'OWNER', ?, ?, ?, ?, ?, " + expiresAtSql + ", 0) RETURNING id",
                Long.class, reservation, VISIT, VISIT.plusHours(1), proposed, proposed.plusHours(1), status);
    }

    // 중복 키는 ReservationNotifier와 같은 모양 - RESERVATION:{예약ID}:{사건}:{수신자}:{채널}
    private long notification(String eventType, long reservation, String event, String recipient, String channel) {
        return jdbc.queryForObject("INSERT INTO notification (owner_id, recipient, event_type, ref_type, ref_id, "
                        + "channel, dedupe_key) VALUES (?, ?, ?, 'RESERVATION', ?, ?, ?) RETURNING id", Long.class,
                ownerId, recipient, eventType, reservation, channel,
                "RESERVATION:" + reservation + ":" + event + ":" + recipient + ":" + channel);
    }

    private void subscribe(String endpoint) {
        jdbc.update("INSERT INTO push_subscription (owner_id, endpoint, p256dh, auth) VALUES (?, ?, 'p', 'a')",
                ownerId, endpoint);
    }

    private static String endpoint() {
        return "https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID();
    }

    // 재시도 간격을 기다리는 대신 생성 시각을 앞으로 당김
    private void ageBy(long id, String interval) {
        jdbc.update("UPDATE notification SET created_at = created_at - CAST(? AS interval) WHERE id = ?", interval, id);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT send_status, attempt_count FROM notification WHERE id = ?", id);
    }

    private int subscriptions(String endpoint) {
        return jdbc.queryForObject("SELECT count(*) FROM push_subscription WHERE endpoint = ?", Integer.class,
                endpoint);
    }
}