package com.ilro.backend.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 마이그레이션(V1·V2)의 DB 제약이 실제로 막는지 확인 (테스트마다 롤백)
// 제약에 걸리면 트랜잭션이 끝나므로, 막혀야 하는 INSERT는 테스트마다 마지막에 한 번만 실행
@SpringBootTest
@Transactional
class SchemaConstraintTest {

    @Autowired
    private JdbcTemplate jdbc;

    private long ownerId;

    @BeforeEach
    void setUp() {
        ownerId = insertOwner();
    }

    @Test
    @DisplayName("같은 업체의 CONFIRMED 예약끼리 시간이 겹치면 저장되지 않는다")
    void confirmedReservationsCannotOverlap() {
        insertReservation(ownerId, null, null, "10:00", "11:00", "CONFIRMED");

        assertThatThrownBy(() -> insertReservation(ownerId, null, null, "10:30", "11:30", "CONFIRMED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ex_reservation_confirmed_overlap");
    }

    @Test
    @DisplayName("맞닿은 시간, 다른 업체, CONFIRMED가 아닌 예약은 겹쳐도 저장된다")
    void nonConflictingReservationsAreAllowed() {
        long otherOwnerId = insertOwner();

        insertReservation(ownerId, null, null, "10:00", "11:00", "CONFIRMED");
        insertReservation(ownerId, null, null, "11:00", "12:00", "CONFIRMED");
        insertReservation(otherOwnerId, null, null, "10:00", "11:00", "CONFIRMED");
        insertReservation(ownerId, null, null, "10:30", "11:30", "REQUESTED");
        insertReservation(ownerId, null, null, "10:30", "11:30", "CANCELED");

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM reservation WHERE owner_id IN (?, ?)", Integer.class, ownerId, otherOwnerId);
        assertThat(count).isEqualTo(5);
    }

    @Test
    @DisplayName("문의 하나에 취소되지 않은 예약은 1건만 둘 수 있다")
    void oneActiveReservationPerInquiry() {
        long inquiryId = insertInquiry(ownerId);
        insertReservation(ownerId, inquiryId, null, "09:00", "10:00", "CANCELED");
        insertReservation(ownerId, inquiryId, null, "10:00", "11:00", "CONFIRMED");

        assertThatThrownBy(() -> insertReservation(ownerId, inquiryId, null, "13:00", "14:00", "REQUESTED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_reservation_active_inquiry");
    }

    @Test
    @DisplayName("다른 업체의 고객은 예약에 연결할 수 없다")
    void reservationCustomerMustBelongToSameOwner() {
        long otherOwnerId = insertOwner();
        long otherCustomerId = jdbc.queryForObject(
                "INSERT INTO customer (owner_id, phone) VALUES (?, ?) RETURNING id", Long.class,
                otherOwnerId, unique("c"));

        assertThatThrownBy(() -> insertReservation(ownerId, null, otherCustomerId, "10:00", "11:00", "CONFIRMED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_reservation_customer_same_owner");
    }

    @Test
    @DisplayName("진행 중인 변경 요청은 예약당 1건만 둘 수 있다")
    void oneActiveChangePerReservation() {
        long reservationId = insertReservation(ownerId, null, null, "10:00", "11:00", "CONFIRMED");
        insertChange(reservationId, "REJECTED");
        insertChange(reservationId, "REQUESTED");

        assertThatThrownBy(() -> insertChange(reservationId, "PROPOSED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_reservation_change_active");
    }

    @Test
    @DisplayName("접수 항목의 현재값은 문의·항목당 1개만 둘 수 있다")
    void oneCurrentValuePerField() {
        long inquiryId = insertInquiry(ownerId);
        insertField(inquiryId, "manufacturer", false);
        insertField(inquiryId, "manufacturer", true);
        insertField(inquiryId, "model", true);

        assertThatThrownBy(() -> insertField(inquiryId, "manufacturer", true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_inquiry_field_current");
    }

    @Test
    @DisplayName("상태값 목록에 없는 값은 저장되지 않는다")
    void unknownStatusIsRejected() {
        assertThatThrownBy(() -> insertReservation(ownerId, null, null, "10:00", "11:00", "DONE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_reservation_status");
    }

    @Test
    @DisplayName("행을 수정하면 updated_at이 트리거로 바뀐다")
    void updatedAtIsRefreshedOnUpdate() {
        long customerId = jdbc.queryForObject(
                "INSERT INTO customer (owner_id, phone, created_at, updated_at) "
                        + "VALUES (?, ?, '2000-01-01T00:00:00Z', '2000-01-01T00:00:00Z') RETURNING id",
                Long.class, ownerId, unique("c"));

        jdbc.update("UPDATE customer SET name = '김고객' WHERE id = ?", customerId);

        Instant updatedAt = jdbc.queryForObject(
                "SELECT updated_at FROM customer WHERE id = ?", Instant.class, customerId);
        assertThat(updatedAt).isAfter(Instant.parse("2000-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("AI 입력 순번은 0에서 시작하고 음수가 될 수 없다")
    void aiInputSeqStartsAtZero() {
        long inquiryId = insertInquiry(ownerId);
        assertThat(jdbc.queryForObject("SELECT ai_input_seq FROM inquiry WHERE id = ?", Long.class, inquiryId))
                .isZero();

        assertThatThrownBy(() -> jdbc.update("UPDATE inquiry SET ai_input_seq = -1 WHERE id = ?", inquiryId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_inquiry_ai_input_seq");
    }

    @Test
    @DisplayName("문의 토큰 칸은 inquiry에서 빠지고 auth_token으로 옮겨졌다")
    void inquiryTokenColumnsMovedToAuthToken() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'inquiry'",
                String.class);

        assertThat(columns).contains("ai_input_seq").doesNotContain("token_hash", "expires_at");
    }

    @Test
    @DisplayName("토큰 종류마다 정해진 대상 칸 조합이면 저장된다")
    void authTokenWithMatchingSubjectIsAllowed() {
        long adminId = jdbc.queryForObject(
                "INSERT INTO admin (name, phone) VALUES ('운영자', ?) RETURNING id", Long.class, unique("a"));
        long inquiryId = insertInquiry(ownerId);

        insertAuthToken("OWNER", ownerId, null, null, null, null);
        insertAuthToken("ADMIN", null, adminId, null, null, null);
        insertAuthToken("INQUIRY", null, null, inquiryId, null, null);
        insertAuthToken("INQUIRY", ownerId, null, inquiryId, null, null);
        insertAuthToken("CUSTOMER", ownerId, null, null, "010-1234-5678", "RESERVATION_LOOKUP");
        insertAuthToken("CUSTOMER", ownerId, null, null, "010-1234-5678", "INQUIRY_ACCESS");

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM auth_token WHERE owner_id = ? OR admin_id = ? OR inquiry_id = ?",
                Integer.class, ownerId, adminId, inquiryId);
        assertThat(count).isEqualTo(6);
    }

    @Test
    @DisplayName("사장님 토큰에 문의를 같이 넣으면 저장되지 않는다")
    void ownerTokenCannotPointToInquiry() {
        long inquiryId = insertInquiry(ownerId);

        assertThatThrownBy(() -> insertAuthToken("OWNER", ownerId, null, inquiryId, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_auth_token_subject");
    }

    @Test
    @DisplayName("고객 토큰은 조회 범위(scope)가 없으면 저장되지 않는다")
    void customerTokenRequiresScope() {
        assertThatThrownBy(() -> insertAuthToken("CUSTOMER", ownerId, null, null, "010-1234-5678", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_auth_token_subject");
    }

    @Test
    @DisplayName("예약 요청 키와 요청 지문은 같이 있거나 같이 없어야 한다")
    void idempotencyKeyAndHashComeTogether() {
        jdbc.update(
                "INSERT INTO reservation (owner_id, start_at, end_at, idempotency_key, idempotency_hash) "
                        + "VALUES (?, '2099-01-05T10:00:00+09:00', '2099-01-05T11:00:00+09:00', ?, ?)",
                ownerId, unique("idem"), "a".repeat(64));
        insertReservation(ownerId, null, null, "12:00", "13:00", "REQUESTED");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO reservation (owner_id, start_at, end_at, idempotency_key) "
                        + "VALUES (?, '2099-01-05T14:00:00+09:00', '2099-01-05T15:00:00+09:00', ?)",
                ownerId, unique("idem")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_reservation_idempotency_pair");
    }

    @Test
    @DisplayName("실패·형식 오류·오래된 AI 판단은 할 일(action) 없이 저장된다")
    void failedAiDecisionsMayHaveNoAction() {
        long inquiryId = insertInquiry(ownerId);
        insertAiDecision(inquiryId, "FAILED", null);
        insertAiDecision(inquiryId, "TIMEOUT", null);
        insertAiDecision(inquiryId, "INVALID_OUTPUT", null);
        insertAiDecision(inquiryId, "STALE", "ASK_MORE_INFO");
        insertAiDecision(inquiryId, "SUCCESS", "ASK_MORE_INFO");

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ai_decision WHERE inquiry_id = ?", Integer.class, inquiryId);
        assertThat(count).isEqualTo(5);
    }

    @Test
    @DisplayName("성공한 AI 판단은 할 일(action)이 없으면 저장되지 않는다")
    void successfulAiDecisionRequiresAction() {
        long inquiryId = insertInquiry(ownerId);

        assertThatThrownBy(() -> insertAiDecision(inquiryId, "SUCCESS", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_ai_decision_success_action");
    }

    @Test
    @DisplayName("AI 판단 상태값 목록에 없는 값은 저장되지 않는다")
    void unknownAiDecisionStatusIsRejected() {
        long inquiryId = insertInquiry(ownerId);

        assertThatThrownBy(() -> insertAiDecision(inquiryId, "SKIPPED", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_ai_decision_status");
    }

    @Test
    @DisplayName("AI 판단의 입력 순번은 음수가 될 수 없다")
    void aiDecisionInputVersionCannotBeNegative() {
        long inquiryId = insertInquiry(ownerId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO ai_decision (inquiry_id, stage, decided_by, status, input_version) "
                        + "VALUES (?, 'DECIDE', 'EXTERNAL_API', 'FAILED', -1)", inquiryId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_ai_decision_input_version");
    }

    @Test
    @DisplayName("토큰 만료 시각은 발급 시각보다 뒤여야 한다")
    void authTokenMustExpireAfterCreation() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO auth_token (token_hash, token_type, owner_id, created_at, expires_at) "
                        + "VALUES (?, 'OWNER', ?, '2099-01-05T10:00:00Z', '2099-01-05T10:00:00Z')",
                unique("token"), ownerId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_auth_token_expiry");
    }

    @Test
    @DisplayName("같은 토큰 해시는 두 번 저장할 수 없다")
    void authTokenHashIsUnique() {
        String tokenHash = unique("token");
        jdbc.update("INSERT INTO auth_token (token_hash, token_type, owner_id, expires_at) "
                + "VALUES (?, 'OWNER', ?, CURRENT_TIMESTAMP + interval '1 day')", tokenHash, ownerId);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO auth_token (token_hash, token_type, owner_id, expires_at) "
                + "VALUES (?, 'OWNER', ?, CURRENT_TIMESTAMP + interval '1 day')", tokenHash, ownerId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_auth_token_hash");
    }

    @Test
    @DisplayName("만료·종료된 변경 요청은 진행 중으로 세지 않아 새 요청을 받을 수 있다")
    void expiredAndClosedChangesAreNotActive() {
        long reservationId = insertReservation(ownerId, null, null, "10:00", "11:00", "CONFIRMED");
        insertChange(reservationId, "EXPIRED");
        jdbc.update(
                "INSERT INTO reservation_change (reservation_id, change_type, requested_by, prev_start_at, prev_end_at, "
                        + "status, close_reason, resolved_by, base_version) VALUES (?, 'CHANGE', 'OWNER', "
                        + "'2099-01-05T10:00:00+09:00', '2099-01-05T11:00:00+09:00', 'CLOSED', 'OWNER_CLOSED', 'OWNER', 0)",
                reservationId);
        insertChange(reservationId, "REQUESTED");

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM reservation_change WHERE reservation_id = ?", Integer.class, reservationId);
        assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName("종료(CLOSED)한 변경 요청은 종료 이유가 없으면 저장되지 않는다")
    void closedChangeRequiresReason() {
        long reservationId = insertReservation(ownerId, null, null, "10:00", "11:00", "CONFIRMED");

        assertThatThrownBy(() -> insertChange(reservationId, "CLOSED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_reservation_change_closed");
    }

    @Test
    @DisplayName("취소는 변경 요청으로 만들지 않는다 (change_type은 CHANGE만)")
    void cancelIsNotAChangeType() {
        long reservationId = insertReservation(ownerId, null, null, "10:00", "11:00", "CONFIRMED");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO reservation_change (reservation_id, change_type, requested_by, prev_start_at, prev_end_at, "
                        + "base_version) VALUES (?, 'CANCEL', 'CUSTOMER', "
                        + "'2099-01-05T10:00:00+09:00', '2099-01-05T11:00:00+09:00', 0)", reservationId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_reservation_change_type");
    }

    @Test
    @DisplayName("알림은 SKIPPED·세분화한 사건 종류로 저장되고, 목록에 없는 상태는 막힌다")
    void notificationAcceptsSkippedAndDetailedEvents() {
        insertNotification("RESERVATION_CONFIRMED", "SKIPPED");
        insertNotification("CHANGE_PROPOSED", "PENDING");
        insertNotification("CHANGE_APPLIED", "SENT");
        insertNotification("RESERVATION_CANCELED", "FAILED");

        assertThatThrownBy(() -> insertNotification("RESERVATION_CONFIRMED", "CANCELED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_notification_send_status");
    }

    @Test
    @DisplayName("문자 인증의 대상 문의는 있는 문의여야 한다")
    void verificationTargetInquiryMustExist() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO phone_verification (phone, purpose, code_hash, send_status, expires_at, target_inquiry_id, "
                        + "request_ip) VALUES ('01012345678', 'CUSTOMER_VERIFY', 'hash', 'SENT', "
                        + "CURRENT_TIMESTAMP + interval '5 minutes', -1, '203.0.113.7')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_phone_verification_inquiry");
    }

    @Test
    @DisplayName("접수 항목의 근거 메시지는 같은 문의의 메시지여야 한다")
    void fieldMessageMustBelongToSameInquiry() {
        long inquiryId = insertInquiry(ownerId);
        long otherInquiryId = insertInquiry(ownerId);
        long otherMessageId = jdbc.queryForObject(
                "INSERT INTO message (inquiry_id, sender, content) VALUES (?, 'CUSTOMER', '안 켜져요') RETURNING id",
                Long.class, otherInquiryId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO inquiry_field (inquiry_id, field_key, source, status, message_id) "
                        + "VALUES (?, 'symptom', 'CUSTOMER', 'UNCONFIRMED', ?)", inquiryId, otherMessageId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_inquiry_field_message_same_inquiry");
    }

    @Test
    @DisplayName("작업별 자동 예약 허용은 기본 꺼짐이고, 제공하지 않는 작업은 허용할 수 없다")
    void autoBookingAllowedOnlyForActiveWorkType() {
        long workTypeId = jdbc.queryForObject(
                "INSERT INTO owner_work_type (owner_id, work_category, duration_minutes) VALUES (?, 'REPAIR', 60) "
                        + "RETURNING id", Long.class, ownerId);
        assertThat(jdbc.queryForObject(
                "SELECT auto_booking_allowed FROM owner_work_type WHERE id = ?", Boolean.class, workTypeId)).isFalse();
        jdbc.update("UPDATE owner_work_type SET auto_booking_allowed = true WHERE id = ?", workTypeId);

        assertThatThrownBy(() -> jdbc.update("UPDATE owner_work_type SET is_active = false WHERE id = ?", workTypeId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_owner_work_type_auto_booking");
    }

    private long insertOwner() {
        return jdbc.queryForObject(
                "INSERT INTO owner (business_name, phone, link_token) VALUES ('테스트보일러', ?, ?) RETURNING id",
                Long.class, unique("o"), unique("link"));
    }

    private long insertInquiry(long ownerId) {
        return jdbc.queryForObject(
                "INSERT INTO inquiry (owner_id) VALUES (?) RETURNING id", Long.class, ownerId);
    }

    private void insertAuthToken(String tokenType, Long ownerId, Long adminId, Long inquiryId, String customerPhone,
                                 String scope) {
        jdbc.update(
                "INSERT INTO auth_token (token_hash, token_type, owner_id, admin_id, inquiry_id, customer_phone, scope, "
                        + "expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP + interval '1 day')",
                unique("token"), tokenType, ownerId, adminId, inquiryId, customerPhone, scope);
    }

    private void insertAiDecision(long inquiryId, String status, String action) {
        jdbc.update(
                "INSERT INTO ai_decision (inquiry_id, stage, action, decided_by, status, input_version, request_id) "
                        + "VALUES (?, 'DECIDE', ?, 'EXTERNAL_API', ?, 0, ?)",
                inquiryId, action, status, unique("req"));
    }

    // 시각은 2099-01-05(월) 한국 시간 기준 "HH:mm"
    private long insertReservation(long ownerId, Long inquiryId, Long customerId, String start, String end,
                                   String status) {
        return jdbc.queryForObject(
                "INSERT INTO reservation (owner_id, inquiry_id, customer_id, start_at, end_at, status) "
                        + "VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz, ?) RETURNING id",
                Long.class, ownerId, inquiryId, customerId,
                "2099-01-05T" + start + ":00+09:00", "2099-01-05T" + end + ":00+09:00", status);
    }

    private void insertChange(long reservationId, String status) {
        jdbc.update(
                "INSERT INTO reservation_change (reservation_id, change_type, requested_by, prev_start_at, prev_end_at, "
                        + "status, base_version) VALUES (?, 'CHANGE', 'CUSTOMER', "
                        + "'2099-01-05T10:00:00+09:00', '2099-01-05T11:00:00+09:00', ?, 0)",
                reservationId, status);
    }

    private void insertNotification(String eventType, String sendStatus) {
        jdbc.update(
                "INSERT INTO notification (owner_id, recipient, event_type, ref_type, ref_id, channel, dedupe_key, "
                        + "send_status) VALUES (?, 'CUSTOMER', ?, 'RESERVATION', 1, 'SMS', ?, ?)",
                ownerId, eventType, unique("dedupe"), sendStatus);
    }

    private void insertField(long inquiryId, String fieldKey, boolean isCurrent) {
        jdbc.update(
                "INSERT INTO inquiry_field (inquiry_id, field_key, field_value, source, status, is_current) "
                        + "VALUES (?, ?, '경동나비엔', 'CUSTOMER', 'UNCONFIRMED', ?)",
                inquiryId, fieldKey, isCurrent);
    }

    // UNIQUE 칸이 로컬 DB에 남아 있는 데이터와 겹치지 않게 임의 값 사용
    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
