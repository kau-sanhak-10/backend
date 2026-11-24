package com.ilro.backend.domain.reservation;

import com.ilro.backend.domain.reservation.dto.ReservationCompleteRequest;
import com.ilro.backend.domain.reservation.dto.ReservationCompleteResponse;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.domain.reservation.exception.ReservationErrorCode;
import com.ilro.backend.domain.reservation.repository.ReservationChangeRepository;
import com.ilro.backend.domain.reservation.repository.ReservationHistoryRepository;
import com.ilro.backend.domain.reservation.repository.ReservationRepository;
import com.ilro.backend.domain.reservation.service.ReservationCompleteService;
import com.ilro.backend.domain.reservation.service.ScheduleWriter;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.support.TestAccounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 작업 완료·완료 정정 (B-25, 명세 4.6, BE-13)
@SpringBootTest
class ReservationCompleteFlowTest {

    private static final OffsetDateTime PAST = OffsetDateTime.parse("2026-01-05T10:00:00+09:00");
    private static final OffsetDateTime FUTURE = OffsetDateTime.parse("2099-01-05T10:00:00+09:00");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScheduleWriter writer;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationChangeRepository changeRepository;

    @Autowired
    private ReservationHistoryRepository historyRepository;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private long ownerId;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        accounts = new TestAccounts(jdbc);
        ownerId = accounts.approvedOwner(accounts.newPhone());
        ownerToken = accounts.ownerToken(ownerId);
    }

    @AfterEach
    void tearDown() {
        accounts.cleanUp();
    }

    @Test
    @DisplayName("완료 - COMPLETED·버전 +1·이력, 다시 누르면 200 alreadyCompleted로 이력을 더 만들지 않음, 알림 없음")
    void complete() throws Exception {
        long id = reservation(PAST, "CONFIRMED");

        complete(id, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.version").value(1))
                .andExpect(jsonPath("$.result.alreadyCompleted").value(false))
                .andExpect(jsonPath("$.result.closedChangeIds", hasSize(0)));
        // 두 번째 요청은 옛 버전을 들고 옴
        complete(id, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value(1))
                .andExpect(jsonPath("$.result.alreadyCompleted").value(true));

        assertThat(history(id)).containsExactly("CONFIRMED>COMPLETED:OWNER:-");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE owner_id = ?", Integer.class,
                ownerId)).isZero();
    }

    @Test
    @DisplayName("방문 시작 시각 전이면 409 RESERVATION_NOT_STARTED + startAt, 예약은 그대로")
    void beforeStart() throws Exception {
        long id = reservation(FUTURE, "CONFIRMED");

        complete(id, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_STARTED"))
                .andExpect(jsonPath("$.result.startAt").value("2099-01-05T10:00:00+09:00"));

        assertUnchanged(id, "CONFIRMED");
    }

    @Test
    @DisplayName("시작 1초 전은 막고 시작 정각은 완료")
    void exactStart() {
        long id = reservation(FUTURE, "CONFIRMED");
        ReservationCompleteRequest request = new ReservationCompleteRequest(0);

        assertThatThrownBy(() -> serviceAt(FUTURE.minusSeconds(1)).complete(ownerId, id, request))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ReservationErrorCode.RESERVATION_NOT_STARTED));
        ReservationCompleteResponse response = serviceAt(FUTURE).complete(ownerId, id, request);

        assertThat(response.status()).isEqualTo(ReservationStatus.COMPLETED);
    }

    @Test
    @DisplayName("취소 예약은 완료·정정 모두 409 RESERVATION_CANCELED")
    void canceled() throws Exception {
        long id = reservation(PAST, "CANCELED");

        complete(id, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESERVATION_CANCELED"));
        correct(id, 0, "잘못 누름").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_CANCELED"));

        assertUnchanged(id, "CANCELED");
    }

    @Test
    @DisplayName("확정 전(REQUESTED) 예약은 완료하지 못함")
    void requested() throws Exception {
        long id = reservation(PAST, "REQUESTED");

        complete(id, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_CHANGEABLE"))
                .andExpect(jsonPath("$.result.status").value("REQUESTED"));

        assertUnchanged(id, "REQUESTED");
    }

    @Test
    @DisplayName("버전이 다르면 완료·정정 모두 409 + 최신 예약")
    void versionConflict() throws Exception {
        long id = reservation(PAST, "CONFIRMED");
        complete(id, 7)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.result.reservation.version").value(0));
        assertUnchanged(id, "CONFIRMED");

        complete(id, 0).andExpect(status().isOk());
        correct(id, 0, "잘못 누름")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.result.reservation.version").value(1));
        assertThat(statusOf(id)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("완료하면 진행 중 변경 요청을 OWNER_CLOSED로 닫고, 정정해도 다시 열지 않음")
    void closesActiveChange() throws Exception {
        for (String changeStatus : List.of("REQUESTED", "PROPOSED")) {
            long id = reservation(PAST.plusDays(changeStatus.length()), "CONFIRMED");
            long changeId = change(id, changeStatus);

            complete(id, 0)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.closedChangeIds", contains((int) changeId)));
            correct(id, 1, "잘못 누름").andExpect(status().isOk());

            assertThat(jdbc.queryForMap("SELECT status, close_reason, resolved_by FROM reservation_change "
                    + "WHERE id = ?", changeId))
                    .containsEntry("status", "CLOSED")
                    .containsEntry("close_reason", "OWNER_CLOSED")
                    .containsEntry("resolved_by", "OWNER");
        }
    }

    @Test
    @DisplayName("정정 - CONFIRMED·버전 +1·사유 이력, 다시 완료할 수 있고 완료 전 예약의 정정은 409")
    void correct() throws Exception {
        long id = reservation(PAST, "CONFIRMED");
        correct(id, 0, "잘못 누름")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_COMPLETED"))
                .andExpect(jsonPath("$.result.status").value("CONFIRMED"));

        complete(id, 0).andExpect(status().isOk());
        correct(id, 1, "  다른 예약을 누름  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.result.version").value(2));
        complete(id, 2).andExpect(status().isOk());

        assertThat(history(id)).containsExactly("CONFIRMED>COMPLETED:OWNER:-",
                "COMPLETED>CONFIRMED:OWNER:다른 예약을 누름", "CONFIRMED>COMPLETED:OWNER:-");
    }

    @Test
    @DisplayName("정정할 시간에 다른 확정 예약이 있으면 409 SLOT_UNAVAILABLE(OVERLAP), 예약·이력은 그대로")
    void correctionOverlap() throws Exception {
        long id = reservation(PAST, "COMPLETED");
        reservation(PAST.plusMinutes(30), "CONFIRMED");

        correct(id, 0, "잘못 누름")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_UNAVAILABLE"))
                .andExpect(jsonPath("$.result.reason").value("OVERLAP"));

        assertUnchanged(id, "COMPLETED");
    }

    @Test
    @DisplayName("버전이 없거나 정정 사유가 비었거나 255자를 넘으면 400")
    void validation() throws Exception {
        long id = reservation(PAST, "CONFIRMED");

        send(ownerToken, id, "complete", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("version"));
        for (String reason : List.of(" ", "a".repeat(256))) {
            correct(id, 0, reason)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.result[0].field").value("reason"));
        }

        assertUnchanged(id, "CONFIRMED");
    }

    @Test
    @DisplayName("다른 업체 예약은 완료·정정 모두 404")
    void otherOwner() throws Exception {
        long id = reservation(PAST, "CONFIRMED");
        String otherToken = accounts.ownerToken(accounts.approvedOwner(accounts.newPhone()));

        send(otherToken, id, "complete", "{\"version\":0}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        send(otherToken, id, "complete-correction", "{\"version\":0,\"reason\":\"잘못 누름\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));

        assertUnchanged(id, "CONFIRMED");
    }

    // 정해진 시각에 멈춘 서비스
    private ReservationCompleteService serviceAt(OffsetDateTime now) {
        return new ReservationCompleteService(writer, reservationRepository, changeRepository, historyRepository,
                Clock.fixed(now.toInstant(), ZoneOffset.UTC));
    }

    private ResultActions complete(long id, int version) throws Exception {
        return send(ownerToken, id, "complete", "{\"version\":" + version + "}");
    }

    private ResultActions correct(long id, int version, String reason) throws Exception {
        return send(ownerToken, id, "complete-correction",
                "{\"version\":" + version + ",\"reason\":\"" + reason + "\"}");
    }

    private ResultActions send(String token, long id, String action, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/owner/reservations/" + id + "/" + action)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long reservation(OffsetDateTime startAt, String status) {
        return jdbc.queryForObject("INSERT INTO reservation (owner_id, start_at, end_at, status) "
                + "VALUES (?, ?, ?, ?) RETURNING id", Long.class, ownerId, startAt, startAt.plusHours(1), status);
    }

    private long change(long reservationId, String status) {
        return jdbc.queryForObject("INSERT INTO reservation_change (reservation_id, change_type, requested_by, "
                        + "prev_start_at, prev_end_at, proposed_start_at, proposed_end_at, status, expires_at, "
                        + "base_version) VALUES (?, 'CHANGE', 'CUSTOMER', ?, ?, ?, ?, ?, "
                        + "CURRENT_TIMESTAMP + interval '1 day', 0) RETURNING id", Long.class, reservationId, PAST,
                PAST.plusHours(1), FUTURE, FUTURE.plusHours(1), status);
    }

    private void assertUnchanged(long id, String status) {
        assertThat(jdbc.queryForMap("SELECT status, version FROM reservation WHERE id = ?", id))
                .containsEntry("status", status)
                .containsEntry("version", 0);
        assertThat(history(id)).isEmpty();
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id);
    }

    private List<String> history(long id) {
        return jdbc.queryForList("SELECT coalesce(from_status, '-') || '>' || to_status || ':' || changed_by || ':' "
                + "|| coalesce(reason, '-') FROM reservation_history WHERE reservation_id = ? ORDER BY id",
                String.class, id);
    }
}
