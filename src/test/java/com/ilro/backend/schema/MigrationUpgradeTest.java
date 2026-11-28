package com.ilro.backend.schema;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 마이그레이션을 빈 DB와 V1 데이터가 있는 DB 모두에 적용해 보는 테스트
// 기본 스키마(public)와 섞이지 않게 따로 만든 스키마에서 돌리고 끝나면 지움
// (@SpringBootTest라 public이 먼저 마이그레이션돼 btree_gist 확장은 public에 이미 있음)
@SpringBootTest
class MigrationUpgradeTest {

    private static final String SCHEMA = "migration_upgrade_check";
    private static final String S = SCHEMA + ".";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void dropSchema() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("빈 DB에 처음부터 끝까지 마이그레이션된다")
    void migratesEmptyDatabase() {
        flyway(null).migrate();

        assertThat(currentVersion()).isEqualTo("8");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = ? AND table_name <> "
                        + "'flyway_schema_history'", Integer.class, SCHEMA)).isEqualTo(26);
    }

    @Test
    @DisplayName("V1 데이터가 있는 DB도 V8까지 올라가며 이전 데이터의 변환 규칙을 유지한다")
    void migratesV1Data() {
        flyway("1").migrate();
        Instant now = Instant.now();

        long autoOwner = insert("INSERT INTO " + S + "owner (business_name, phone, link_token, auto_booking_enabled) "
                + "VALUES ('자동보일러', '01011110000', 'link-auto', true) RETURNING id");
        long manualOwner = insert("INSERT INTO " + S + "owner (business_name, phone, link_token) "
                + "VALUES ('수동보일러', '01022220000', 'link-manual') RETURNING id");
        long autoRepair = insertWorkType(autoOwner, "REPAIR", true);
        long autoLeak = insertWorkType(autoOwner, "LEAK", false);
        long manualRepair = insertWorkType(manualOwner, "REPAIR", true);

        long liveInquiry = insertInquiry(autoOwner, "hash-live", "CURRENT_TIMESTAMP + interval '3 days'", "COLLECTING", false);
        insertInquiry(autoOwner, "hash-expired", "CURRENT_TIMESTAMP - interval '1 hour'", "COLLECTING", false);
        insertInquiry(autoOwner, "hash-no-expiry", "NULL", "COLLECTING", false);
        insertInquiry(autoOwner, "hash-deleted", "CURRENT_TIMESTAMP + interval '1 day'", "COLLECTING", true);
        insertInquiry(autoOwner, "hash-closed", "CURRENT_TIMESTAMP + interval '1 day'", "CLOSED", false);

        long keyed = insertReservation(autoOwner, "CONFIRMED", "2099-01-05T10:00:00+09:00", "'idem-v1'");
        long canceled = insertReservation(autoOwner, "CANCELED", "2099-01-05T12:00:00+09:00", "NULL");
        long farFuture = insertReservation(autoOwner, "CONFIRMED", "2099-01-06T10:00:00+09:00", "NULL");
        long soon = insertReservation(manualOwner, "CONFIRMED", "2099-01-07T10:00:00+09:00", "NULL");

        long pendingCancel = insertChange(keyed, "CANCEL", "REQUESTED", "NULL", "'환불 원해요'");
        long appliedCancel = insertChange(canceled, "CANCEL", "APPLIED", "NULL", "NULL");
        long longProposal = insertChange(farFuture, "CHANGE", "PROPOSED", "'2099-01-08T10:00:00+09:00'::timestamptz",
                "NULL");
        long lateProposal = insertChange(soon, "CHANGE", "PROPOSED", "CURRENT_TIMESTAMP + interval '1 hour'", "NULL");
        // 고객이 주소 변경을 요청한 행 - 제안 전(취소 요청)과 제안 뒤
        jdbc.update("UPDATE " + S + "reservation_change SET proposed_address = '경기 고양시 ○○로 1', "
                + "proposed_lat = 37.65, proposed_lng = 126.85 WHERE id IN (?, ?)", pendingCancel, longProposal);

        jdbc.update("INSERT INTO " + S + "ai_decision (inquiry_id, stage, action, decided_by, status) "
                + "VALUES (?, 'DECIDE', 'ASK_MORE_INFO', 'EXTERNAL_API', 'SUCCESS')", liveInquiry);

        // 재시도를 기다리던 실패 알림과 이미 보낸 알림
        long failedNotification = insert("INSERT INTO " + S + "notification (owner_id, recipient, event_type, "
                + "ref_type, ref_id, channel, dedupe_key, send_status, attempt_count) VALUES (?, 'CUSTOMER', "
                + "'NEW_RESERVATION', 'RESERVATION', ?, 'SMS', 'v1-failed', 'FAILED', 1) RETURNING id",
                autoOwner, keyed);
        long sentNotification = insert("INSERT INTO " + S + "notification (owner_id, recipient, event_type, "
                + "ref_type, ref_id, channel, dedupe_key, send_status, attempt_count) VALUES (?, 'CUSTOMER', "
                + "'NEW_RESERVATION', 'RESERVATION', ?, 'SMS', 'v1-sent', 'SENT', 1) RETURNING id",
                autoOwner, keyed);

        // 예약에 남긴 작업 메모
        insertWorkNote(keyed, "배기통 점검 필요");

        flyway(null).migrate();

        assertThat(currentVersion()).isEqualTo("8");
        // V7: 기존 작업 메모는 그대로, 같은 예약에 두 번째 메모는 막음
        assertThat(jdbc.queryForObject("SELECT content FROM " + S + "work_note WHERE reservation_id = ?",
                String.class, keyed)).isEqualTo("배기통 점검 필요");
        assertThatThrownBy(() -> insertWorkNote(keyed, "두 번째 메모"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_work_note_reservation");
        // V6: 재시도를 기다리던 실패 알림은 다음 시도 시각이 생겨 바로 다시 골라짐, 보낸 알림은 비어 있음
        assertThat(jdbc.queryForObject("SELECT next_attempt_at IS NOT NULL AND next_attempt_at <= CURRENT_TIMESTAMP "
                + "FROM " + S + "notification WHERE id = ?", Boolean.class, failedNotification)).isTrue();
        assertThat(jdbc.queryForObject("SELECT next_attempt_at IS NULL FROM " + S + "notification WHERE id = ?",
                Boolean.class, sentNotification)).isTrue();
        // V5: 고객이 요청한 주소는 요청 주소 칸으로 옮기고, 제안이 없는 행은 제안 주소를 비움. 제안한 행은 제안 주소 유지
        assertThat(jdbc.queryForMap("SELECT requested_address, requested_lat, proposed_address, proposed_lat FROM "
                + S + "reservation_change WHERE id = ?", pendingCancel))
                .containsEntry("requested_address", "경기 고양시 ○○로 1")
                .containsEntry("proposed_address", null)
                .containsEntry("proposed_lat", null)
                .extractingByKey("requested_lat").isNotNull();
        assertThat(jdbc.queryForMap("SELECT requested_address, proposed_address FROM " + S
                + "reservation_change WHERE id = ?", longProposal))
                .containsEntry("requested_address", "경기 고양시 ○○로 1")
                .containsEntry("proposed_address", "경기 고양시 ○○로 1");
        assertThat(jdbc.queryForObject("SELECT requested_address FROM " + S + "reservation_change WHERE id = ?",
                String.class, lateProposal)).isNull();
        // V5: 기존 예약은 영업시간 밖 등록이 아님
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + S + "reservation WHERE outside_business_hours",
                Integer.class)).isZero();
        // V3: 기존 문의는 방문정보를 누가 정했는지 기록이 없음 (고객 값으로 봄)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + S + "inquiry WHERE visit_info_source IS NOT NULL",
                Integer.class)).isZero();
        // V4: 기존 문의는 그대로 남고, 신규 접수 요청 지문은 비어 있음.
        assertThat(count("inquiry")).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + S + "inquiry WHERE start_request_hash IS NOT NULL",
                Integer.class)).isZero();

        // 3. 쓸 수 있는 문의 토큰만 옮기고, 24시간을 넘지 않게 줄임
        Map<String, Object> token = jdbc.queryForMap(
                "SELECT token_type, inquiry_id FROM " + S + "auth_token WHERE token_hash = 'hash-live'");
        assertThat(token.get("token_type")).isEqualTo("INQUIRY");
        assertThat(((Number) token.get("inquiry_id")).longValue()).isEqualTo(liveInquiry);
        Instant tokenExpiry = jdbc.queryForObject(
                "SELECT expires_at FROM " + S + "auth_token WHERE token_hash = 'hash-live'", Instant.class);
        assertThat(tokenExpiry).isBetween(now, now.plus(Duration.ofHours(24)).plusSeconds(60));
        assertThat(count("auth_token")).isEqualTo(1);

        // 4. 지문을 만들 수 없는 V1 요청 키는 무효화
        assertThat(jdbc.queryForObject(
                "SELECT idempotency_key FROM " + S + "reservation WHERE id = ?", String.class, keyed)).isNull();

        // 6. V1 취소 요청은 종료로, 제안은 기한을 다시 셈
        Map<String, Object> closed = change(pendingCancel);
        assertThat(closed).containsEntry("change_type", "CHANGE").containsEntry("status", "CLOSED")
                .containsEntry("close_reason", "OWNER_CLOSED").containsEntry("resolved_by", "SYSTEM")
                .containsEntry("request_note", "[V1 취소 요청] 환불 원해요");
        assertThat(closed.get("resolved_at")).isNotNull();
        assertThat(change(appliedCancel)).containsEntry("status", "CLOSED")
                .containsEntry("close_reason", "RESERVATION_CANCELED");

        Map<String, Object> proposal = change(longProposal);
        assertThat(proposal).containsEntry("status", "PROPOSED");
        Instant proposalExpiry = jdbc.queryForObject(
                "SELECT expires_at FROM " + S + "reservation_change WHERE id = ?", Instant.class, longProposal);
        assertThat(proposalExpiry).isBetween(now.plus(Duration.ofHours(23)), now.plus(Duration.ofHours(24)).plusSeconds(60));
        assertThat(change(lateProposal)).containsEntry("status", "EXPIRED").containsEntry("resolved_by", "SYSTEM");

        // 5. 기존 AI 판단은 그대로, 새 칸은 비어 있음
        assertThat(jdbc.queryForObject("SELECT input_version FROM " + S + "ai_decision WHERE inquiry_id = ?",
                Long.class, liveInquiry)).isNull();
        assertThat(jdbc.queryForObject("SELECT ai_input_seq FROM " + S + "inquiry WHERE id = ?",
                Long.class, liveInquiry)).isZero();

        // 10. 자동 예약을 켠 업체의 제공 작업만 허용으로 옮김
        assertThat(autoBookingAllowed(autoRepair)).isTrue();
        assertThat(autoBookingAllowed(autoLeak)).isFalse();
        assertThat(autoBookingAllowed(manualRepair)).isFalse();
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private String currentVersion() {
        return jdbc.queryForObject("SELECT version FROM " + S + "flyway_schema_history "
                + "WHERE success ORDER BY installed_rank DESC LIMIT 1", String.class);
    }

    private long insert(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long insertWorkType(long ownerId, String category, boolean active) {
        return insert("INSERT INTO " + S + "owner_work_type (owner_id, work_category, duration_minutes, is_active) "
                + "VALUES (?, ?, 60, ?) RETURNING id", ownerId, category, active);
    }

    private long insertInquiry(long ownerId, String tokenHash, String expiresAtSql, String status, boolean deleted) {
        return insert("INSERT INTO " + S + "inquiry (owner_id, token_hash, expires_at, status, deleted_at) "
                        + "VALUES (?, ?, " + expiresAtSql + ", ?, " + (deleted ? "CURRENT_TIMESTAMP" : "NULL")
                        + ") RETURNING id",
                ownerId, tokenHash, status);
    }

    private long insertReservation(long ownerId, String status, String startAt, String idempotencyKeySql) {
        return insert("INSERT INTO " + S + "reservation (owner_id, status, start_at, end_at, idempotency_key) "
                        + "VALUES (?, ?, ?::timestamptz, ?::timestamptz + interval '1 hour', " + idempotencyKeySql
                        + ") RETURNING id",
                ownerId, status, startAt, startAt);
    }

    // proposedStartSql은 timestamptz SQL 식 또는 NULL
    private long insertChange(long reservationId, String type, String status, String proposedStartSql,
                              String noteSql) {
        String proposedEndSql = "NULL".equals(proposedStartSql) ? "NULL" : "(" + proposedStartSql + ") + interval '1 hour'";
        return insert("INSERT INTO " + S + "reservation_change (reservation_id, change_type, requested_by, "
                        + "request_note, prev_start_at, prev_end_at, proposed_start_at, proposed_end_at, status, "
                        + "base_version) VALUES (?, ?, 'CUSTOMER', " + noteSql + ", '2099-01-05T10:00:00+09:00', "
                        + "'2099-01-05T11:00:00+09:00', " + proposedStartSql + ", " + proposedEndSql + ", ?, 0) "
                        + "RETURNING id",
                reservationId, type, status);
    }

    private void insertWorkNote(long reservationId, String content) {
        jdbc.update("INSERT INTO " + S + "work_note (reservation_id, content) VALUES (?, ?)", reservationId, content);
    }

    private Map<String, Object> change(long changeId) {
        return jdbc.queryForMap("SELECT change_type, status, close_reason, resolved_by, resolved_at, request_note "
                + "FROM " + S + "reservation_change WHERE id = ?", changeId);
    }

    private boolean autoBookingAllowed(long workTypeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT auto_booking_allowed FROM " + S + "owner_work_type WHERE id = ?", Boolean.class, workTypeId));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + S + table, Integer.class);
    }
}
