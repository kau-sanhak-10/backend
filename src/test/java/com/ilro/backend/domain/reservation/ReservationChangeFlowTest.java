package com.ilro.backend.domain.reservation;

import com.ilro.backend.support.TestAccounts;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 예약 변경 요청·제안·수락·거절·닫기와 즉시 취소 (C-15·C-16·C-17·B-19·B-20·B-21, 명세 2.4·5.3·5.4, BE-12)
// 로컬 프로필이라 가짜 지도(FakeMapClient): 같은 좌표는 이동 0분, 서비스 지역을 정했으면 지역 안
@SpringBootTest
class ReservationChangeFlowTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String ADDRESS = "경기 고양시 덕양구 화정로 1";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private long ownerId;
    private String ownerToken;
    private long workTypeId;
    // 다음다음 주 월요일 - 지난 시각이 끼지 않고, 제안 기한은 제안 후 24시간
    private LocalDate monday;

    // 예약 조회 인증을 마친 고객 - 그 번호의 예약만 다룸
    private record Customer(long customerId, String phone, String token) {
    }

    // 평일 09:00~18:00, 토요일 휴무, 점심 12~13시, 하루 3건, 여유시간 15분, 대표 연락처 없음
    // 출발지·예약 주소가 같은 좌표 (가짜 지도 이동 0분), 수리 60분, 서비스 지역 1곳
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        accounts = new TestAccounts(jdbc);
        ownerId = accounts.approvedOwner(accounts.newPhone());
        ownerToken = accounts.ownerToken(ownerId);
        jdbc.update("UPDATE owner SET address = ?, lat = 37.634, lng = 126.832, lunch_start = '12:00', "
                + "lunch_end = '13:00', max_daily_visits = 3, buffer_minutes = 15 WHERE id = ?", ADDRESS, ownerId);
        for (int day = 1; day <= 5; day++) {
            jdbc.update("INSERT INTO owner_business_hour (owner_id, day_of_week, open_time, close_time, is_closed) "
                    + "VALUES (?, ?, '09:00', '18:00', false)", ownerId, day);
        }
        jdbc.update("INSERT INTO owner_business_hour (owner_id, day_of_week, is_closed) VALUES (?, 6, true)",
                ownerId);
        workTypeId = jdbc.queryForObject("INSERT INTO owner_work_type (owner_id, work_category, duration_minutes, "
                + "is_active, auto_booking_allowed) VALUES (?, 'REPAIR', 60, true, true) RETURNING id", Long.class,
                ownerId);
        jdbc.update("INSERT INTO owner_service_area (owner_id, region_code, region_name) "
                + "VALUES (?, '41281', '고양시 덕양구')", ownerId);
        monday = LocalDate.now(KST).with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(1);
    }

    @AfterEach
    void tearDown() {
        accounts.cleanUp();
    }

    @Test
    @DisplayName("요청 → 제안 → 수락 - APPLIED일 때만 예약이 바뀌고 version +1, 이력·알림, 같은 수락은 200")
    void requestProposeAccept() throws Exception {
        Customer customer = customer();
        long reservationId = reservation(customer, monday, 10);

        long changeId = changeId(request(customer, reservationId, "{\"requestNote\":\"오후로 바꾸고 싶어요\","
                + "\"baseVersion\":0}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.status").value("REQUESTED"))
                .andExpect(jsonPath("$.result.expiresAt").doesNotExist()));
        assertThat(jdbc.queryForMap("SELECT change_type, requested_by, request_note, prev_address, base_version, "
                        + "prev_start_at = ? AS prev_kept FROM reservation_change WHERE id = ?",
                at(monday, 10, 0), changeId))
                .containsEntry("change_type", "CHANGE")
                .containsEntry("requested_by", "CUSTOMER")
                .containsEntry("request_note", "오후로 바꾸고 싶어요")
                .containsEntry("prev_address", ADDRESS)
                .containsEntry("base_version", 0)
                .containsEntry("prev_kept", true);
        assertThat(dedupeKeys(reservationId)).containsExactly(
                "RESERVATION:" + reservationId + ":CHANGE:" + changeId + ":OWNER:WEB_PUSH");

        // 진행 중 요청이 있으면 새 요청 못 함 - 그 요청을 같이 돌려줌
        request(customer, reservationId, "{\"requestNote\":\"다시\",\"baseVersion\":0}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANGE_IN_PROGRESS"))
                .andExpect(jsonPath("$.result.change.changeId").value((int) changeId));
        // 사장님이 제안하기 전에는 수락 못 함
        accept(customer, reservationId, changeId, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANGE_NOT_PROPOSED"));

        getAs(ownerToken, "/api/v1/owner/change-requests")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.changes", hasSize(1)))
                .andExpect(jsonPath("$.result.changes[0].changeId").value((int) changeId))
                .andExpect(jsonPath("$.result.changes[0].status").value("REQUESTED"))
                .andExpect(jsonPath("$.result.changes[0].requestNote").value("오후로 바꾸고 싶어요"))
                .andExpect(jsonPath("$.result.changes[0].prevStartAt").value(time(monday, "10:00")))
                .andExpect(jsonPath("$.result.changes[0].customerName").value("김고객"))
                .andExpect(jsonPath("$.result.changes[0].customerPhone").value(customer.phone()));

        Instant before = Instant.now();
        String proposed = propose(reservationId, "{\"changeId\":" + changeId + ",\"proposedStartAt\":\""
                + time(monday, "14:00") + "\",\"note\":\"2시 가능해요\",\"baseVersion\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.changeId").value((int) changeId))
                .andExpect(jsonPath("$.result.status").value("PROPOSED"))
                .andExpect(jsonPath("$.result.expiresAt", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        // 방문이 먼 날이면 기한은 제안 후 24시간
        Instant expiresAt = OffsetDateTime.parse(JsonPath.read(proposed, "$.result.expiresAt")).toInstant();
        assertThat(expiresAt).isBetween(before.plus(24, ChronoUnit.HOURS).minusSeconds(1),
                Instant.now().plus(24, ChronoUnit.HOURS).plusSeconds(1));
        assertThat(jdbc.queryForMap("SELECT status, proposed_start_at = ? AS start_ok, proposed_end_at = ? AS end_ok, "
                        + "proposed_address FROM reservation_change WHERE id = ?",
                at(monday, 14, 0), at(monday, 15, 0), changeId))
                .containsEntry("status", "PROPOSED")
                .containsEntry("start_ok", true)
                .containsEntry("end_ok", true)
                .containsEntry("proposed_address", null);
        // 제안만으로 예약은 그대로
        assertThat(reservationRow(reservationId))
                .containsEntry("version", 0)
                .containsEntry("start_ok", true);
        assertThat(dedupeKeys(reservationId)).contains(
                "RESERVATION:" + reservationId + ":CHANGE_PROPOSED:" + changeId + ":CUSTOMER:SMS");

        accept(customer, reservationId, changeId, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.change.changeId").value((int) changeId))
                .andExpect(jsonPath("$.result.change.status").value("APPLIED"))
                .andExpect(jsonPath("$.result.reservation.reservationId").value((int) reservationId))
                .andExpect(jsonPath("$.result.reservation.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.result.reservation.startAt").value(time(monday, "14:00")))
                .andExpect(jsonPath("$.result.reservation.endAt").value(time(monday, "15:00")))
                .andExpect(jsonPath("$.result.reservation.version").value(1));
        assertThat(jdbc.queryForMap("SELECT status, resolved_by, resolved_at IS NOT NULL AS resolved "
                + "FROM reservation_change WHERE id = ?", changeId))
                .containsEntry("status", "APPLIED")
                .containsEntry("resolved_by", "CUSTOMER")
                .containsEntry("resolved", true);
        assertThat(jdbc.queryForMap("SELECT start_at = ? AS moved, version, travel_time_minutes FROM reservation "
                + "WHERE id = ?", at(monday, 14, 0), reservationId))
                .containsEntry("moved", true)
                .containsEntry("version", 1)
                .containsEntry("travel_time_minutes", 0);
        assertThat(jdbc.queryForList("SELECT from_status || '>' || to_status || ':' || changed_by FROM "
                + "reservation_history WHERE reservation_id = ?", String.class, reservationId))
                .containsExactly("CONFIRMED>CONFIRMED:CUSTOMER");
        assertThat(dedupeKeys(reservationId)).contains(
                "RESERVATION:" + reservationId + ":CHANGE_APPLIED:" + changeId + ":CUSTOMER:SMS",
                "RESERVATION:" + reservationId + ":CHANGE_APPLIED:" + changeId + ":OWNER:WEB_PUSH");

        // 같은 수락을 다시 - 처음 결과 그대로, 더 바꾸지 않음
        accept(customer, reservationId, changeId, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.change.status").value("APPLIED"))
                .andExpect(jsonPath("$.result.reservation.version").value(1));
        assertThat(reservationRow(reservationId)).containsEntry("version", 1);
        // 요청 푸시 1 + 제안 문자 1 + 확정 문자·푸시 2
        assertThat(dedupeKeys(reservationId)).hasSize(4);

        reject(customer, reservationId, changeId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANGE_ALREADY_RESOLVED"));
        // 끝난 뒤에는 새 요청 가능 - 화면이 본 버전이 낡았으면 최신 예약을 돌려줌
        request(customer, reservationId, "{\"requestNote\":\"한 번 더\",\"baseVersion\":0}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.result.reservation.version").value(1))
                .andExpect(jsonPath("$.result.reservation.startAt").value(time(monday, "14:00")));
        request(customer, reservationId, "{\"requestNote\":\"한 번 더\",\"baseVersion\":1}")
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("수락 최종 검사 실패 - 409 + 기존 예약 그대로, 변경은 FAILED로 남고 새 요청 가능")
    void acceptFailsKeepsReservation() throws Exception {
        Customer customer = customer();
        long reservationId = reservation(customer, monday, 10);
        long changeId = proposedChange(customer, reservationId, time(monday, "14:00"));
        // 제안과 수락 사이에 그 시간에 다른 예약이 생김
        reservation(customer(), monday, 14);

        accept(customer, reservationId, changeId, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_UNAVAILABLE"))
                .andExpect(jsonPath("$.result.reason").value("OVERLAP"))
                .andExpect(jsonPath("$.result.change.changeId").value((int) changeId))
                .andExpect(jsonPath("$.result.change.status").value("FAILED"))
                .andExpect(jsonPath("$.result.reservation.startAt").value(time(monday, "10:00")))
                .andExpect(jsonPath("$.result.reservation.version").value(0));
        assertThat(jdbc.queryForMap("SELECT status, resolved_by FROM reservation_change WHERE id = ?", changeId))
                .containsEntry("status", "FAILED")
                .containsEntry("resolved_by", "SYSTEM");
        assertThat(reservationRow(reservationId))
                .containsEntry("version", 0)
                .containsEntry("start_ok", true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation_history WHERE reservation_id = ?",
                Integer.class, reservationId)).isZero();

        accept(customer, reservationId, changeId, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANGE_ALREADY_RESOLVED"));
        request(customer, reservationId, "{\"requestNote\":\"다른 시간\",\"baseVersion\":0}")
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("제안 검사 - 같은 검사기(자기 예약 제외), 2시간 안·분 단위·버전·진행 중 변경·남의 예약")
    void proposeChecks() throws Exception {
        Customer customer = customer();
        long reservationId = reservation(customer, monday, 10);
        reservation(customer(), monday, 15);

        assertProposalBlocked(reservationId, time(monday, "15:00"), "OVERLAP");
        assertProposalBlocked(reservationId, time(monday, "12:00"), "LUNCH_BREAK");
        assertProposalBlocked(reservationId, time(monday, "17:30"), "OUTSIDE_BUSINESS_HOURS");
        assertProposalBlocked(reservationId, time(monday.plusDays(5), "10:00"), "OUTSIDE_BUSINESS_HOURS");
        // 다음 예약 15시와 여유시간 15분이 모자람
        assertProposalBlocked(reservationId, time(monday, "13:50"), "TRAVEL_CONFLICT");

        String soon = OffsetDateTime.now(KST).plusMinutes(90).truncatedTo(ChronoUnit.MINUTES).toString();
        propose(reservationId, proposal(null, soon, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("proposedStartAt"));
        propose(reservationId, proposal(null, monday + "T14:00:30+09:00", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("proposedStartAt"));
        propose(reservationId, proposal(null, time(monday, "14:00"), 3))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.result.reservation.version").value(0));
        propose(reservationId, "{\"proposedStartAt\":\"" + time(monday, "14:00")
                + "\",\"applyAddressChange\":true,\"baseVersion\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("applyAddressChange"));
        // 다른 업체 사장님에게는 없는 예약
        long otherOwner = accounts.approvedOwner(accounts.newPhone());
        mockMvc.perform(post("/api/v1/owner/reservations/" + reservationId + "/proposals")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accounts.ownerToken(otherOwner))
                        .contentType(MediaType.APPLICATION_JSON).content(proposal(null, time(monday, "14:00"), 0)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation_change WHERE reservation_id = ?",
                Integer.class, reservationId)).isZero();

        // 업체 사유 변경 - 자기 예약과 겹치는 시간(10:30)도 됨, 고객 요청 없이 OWNER로 새로 PROPOSED
        long changeId = changeId(propose(reservationId, proposal(null, time(monday, "10:30"), 0))
                .andExpect(status().isOk()));
        assertThat(jdbc.queryForMap("SELECT requested_by, status, request_note FROM reservation_change WHERE id = ?",
                changeId))
                .containsEntry("requested_by", "OWNER")
                .containsEntry("status", "PROPOSED")
                .containsEntry("request_note", null);
        // 진행 중 변경이 있으면 새 제안 대신 그 변경에 다시 제안
        propose(reservationId, proposal(null, time(monday, "14:00"), 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANGE_IN_PROGRESS"))
                .andExpect(jsonPath("$.result.change.changeId").value((int) changeId));
        propose(reservationId, proposal(changeId, time(monday, "13:00"), 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.changeId").value((int) changeId));
        assertThat(jdbc.queryForObject("SELECT proposed_start_at = ? FROM reservation_change WHERE id = ?",
                Boolean.class, at(monday, 13, 0), changeId)).isTrue();
        // 다른 예약의 변경 번호
        long otherReservation = reservation(customer, monday.plusDays(1), 10);
        propose(otherReservation, proposal(changeId, time(monday.plusDays(1), "14:00"), 0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHANGE_NOT_FOUND"));
    }

    @Test
    @DisplayName("주소 변경 - 요청 주소는 proposed_address에만, 제안·수락 때 새 주소로 지역·이동시간 검사 후 바뀜")
    void addressChange() throws Exception {
        Customer customer = customer();
        long reservationId = reservation(customer, monday, 10);

        request(customer, reservationId, "{\"baseVersion\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("requestNote"));
        request(customer, reservationId, "{\"addressChange\":{\"address\":\"경기 고양시 덕양구 ○○로 34\"},"
                + "\"baseVersion\":0}")
                .andExpect(status().isBadRequest());
        long changeId = changeId(request(customer, reservationId, "{\"addressChange\":{\"address\":"
                + "\"경기 고양시 덕양구 ○○로 34\",\"addressDetail\":\"101동 1001호\",\"lat\":37.65,\"lng\":126.85},"
                + "\"baseVersion\":0}").andExpect(status().isCreated()));
        assertThat(jdbc.queryForMap("SELECT proposed_address, proposed_lat, request_note FROM reservation_change "
                + "WHERE id = ?", changeId))
                .containsEntry("proposed_address", "경기 고양시 덕양구 ○○로 34 101동 1001호")
                .containsEntry("request_note", null);
        assertThat(reservationRow(reservationId)).containsEntry("address", ADDRESS);

        // 서비스 지역이 없으면 새 주소는 지역 밖 - 사장님이 지역을 고친 뒤 다시 제안
        jdbc.update("DELETE FROM owner_service_area WHERE owner_id = ?", ownerId);
        propose(reservationId, "{\"changeId\":" + changeId + ",\"proposedStartAt\":\"" + time(monday, "10:00")
                + "\",\"applyAddressChange\":true,\"baseVersion\":0}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OUT_OF_SERVICE_AREA"));
        jdbc.update("INSERT INTO owner_service_area (owner_id, region_code, region_name) "
                + "VALUES (?, '41281', '고양시 덕양구')", ownerId);
        propose(reservationId, "{\"changeId\":" + changeId + ",\"proposedStartAt\":\"" + time(monday, "10:00")
                + "\",\"applyAddressChange\":true,\"baseVersion\":0}")
                .andExpect(status().isOk());

        accept(customer, reservationId, changeId, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.reservation.address").value("경기 고양시 덕양구 ○○로 34 101동 1001호"));
        Map<String, Object> row = jdbc.queryForMap("SELECT address, lat, travel_time_minutes FROM reservation "
                + "WHERE id = ?", reservationId);
        assertThat(row.get("address")).isEqualTo("경기 고양시 덕양구 ○○로 34 101동 1001호");
        assertThat(((Number) row.get("lat")).doubleValue()).isEqualTo(37.65);
        // 출발지에서 새 주소까지 (가짜 지도: 직선거리 기준, 0분 아님)
        assertThat((Integer) row.get("travel_time_minutes")).isPositive();
    }

    @Test
    @DisplayName("거절·사장님 닫기 - 기존 예약 그대로, 같은 요청은 200, 끝난 변경은 CHANGE_ALREADY_RESOLVED")
    void rejectAndClose() throws Exception {
        Customer customer = customer();
        long reservationId = reservation(customer, monday, 10);
        long proposedId = proposedChange(customer, reservationId, time(monday, "14:00"));

        reject(customer, reservationId, proposedId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("REJECTED"));
        reject(customer, reservationId, proposedId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("REJECTED"));
        accept(customer, reservationId, proposedId, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANGE_ALREADY_RESOLVED"))
                .andExpect(jsonPath("$.result.change.status").value("REJECTED"));
        assertThat(jdbc.queryForObject("SELECT resolved_by FROM reservation_change WHERE id = ?", String.class,
                proposedId)).isEqualTo("CUSTOMER");
        assertThat(reservationRow(reservationId)).containsEntry("version", 0).containsEntry("start_ok", true);

        long requestedId = changeId(request(customer, reservationId, "{\"requestNote\":\"다음 주로\","
                + "\"baseVersion\":0}").andExpect(status().isCreated()));
        reject(customer, reservationId, requestedId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANGE_NOT_PROPOSED"));
        // 다른 업체 사장님은 닫을 수 없음
        long otherOwner = accounts.approvedOwner(accounts.newPhone());
        mockMvc.perform(post("/api/v1/owner/change-requests/" + requestedId + "/close")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accounts.ownerToken(otherOwner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHANGE_NOT_FOUND"));
        close(requestedId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("CLOSED"));
        close(requestedId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("CLOSED"));
        assertThat(jdbc.queryForMap("SELECT close_reason, resolved_by FROM reservation_change WHERE id = ?",
                requestedId))
                .containsEntry("close_reason", "OWNER_CLOSED")
                .containsEntry("resolved_by", "OWNER");
        propose(reservationId, proposal(requestedId, time(monday, "14:00"), 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANGE_ALREADY_RESOLVED"));
        close(proposedId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANGE_ALREADY_RESOLVED"));

        // 기본 목록은 사장님이 볼 일(REQUESTED·PROPOSED)만, status로 끝난 것도 봄
        getAs(ownerToken, "/api/v1/owner/change-requests")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.changes", hasSize(0)));
        getAs(ownerToken, "/api/v1/owner/change-requests?status=CLOSED,REJECTED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.changes[*].changeId").value(containsInAnyOrder(
                        (int) requestedId, (int) proposedId)));
        getAs(ownerToken, "/api/v1/owner/change-requests?status=NOPE")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("기한 지난 제안 - 수락하면 409 CHANGE_EXPIRED, EXPIRED로 남고 예약 그대로")
    void expiredProposal() throws Exception {
        Customer customer = customer();
        long reservationId = reservation(customer, monday, 10);
        long changeId = proposedChange(customer, reservationId, time(monday, "14:00"));
        jdbc.update("UPDATE reservation_change SET expires_at = CURRENT_TIMESTAMP - interval '1 minute' "
                + "WHERE id = ?", changeId);

        accept(customer, reservationId, changeId, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANGE_EXPIRED"))
                .andExpect(jsonPath("$.result.change.status").value("EXPIRED"));
        assertThat(jdbc.queryForMap("SELECT status, resolved_by FROM reservation_change WHERE id = ?", changeId))
                .containsEntry("status", "EXPIRED")
                .containsEntry("resolved_by", "SYSTEM");
        assertThat(reservationRow(reservationId)).containsEntry("version", 0).containsEntry("start_ok", true);
        accept(customer, reservationId, changeId, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANGE_ALREADY_RESOLVED"));
    }

    @Test
    @DisplayName("즉시 취소 - CANCELED·version +1·이력·진행 중 변경 CLOSED·상대에게 알림, 다시 취소는 200")
    void cancel() throws Exception {
        Customer customer = customer();
        long reservationId = reservation(customer, monday, 10);
        long changeId = proposedChange(customer, reservationId, time(monday, "14:00"));

        cancelAs(customer.token(), "/api/v1/customer/reservations/" + reservationId + "/cancel",
                "{\"version\":1,\"reason\":\"일정이 생겼어요\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_VERSION_CONFLICT"));
        cancelAs(customer.token(), "/api/v1/customer/reservations/" + reservationId + "/cancel",
                "{\"version\":0,\"reason\":\"일정이 생겼어요\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.reservationId").value((int) reservationId))
                .andExpect(jsonPath("$.result.status").value("CANCELED"))
                .andExpect(jsonPath("$.result.alreadyCanceled").value(false))
                .andExpect(jsonPath("$.result.closedChangeIds").value(contains((int) changeId)));
        assertThat(jdbc.queryForMap("SELECT status, version FROM reservation WHERE id = ?", reservationId))
                .containsEntry("status", "CANCELED")
                .containsEntry("version", 1);
        assertThat(jdbc.queryForMap("SELECT status, close_reason, resolved_by FROM reservation_change WHERE id = ?",
                changeId))
                .containsEntry("status", "CLOSED")
                .containsEntry("close_reason", "RESERVATION_CANCELED")
                .containsEntry("resolved_by", "CUSTOMER");
        assertThat(jdbc.queryForList("SELECT from_status || '>' || to_status || ':' || changed_by || ':' || reason "
                + "FROM reservation_history WHERE reservation_id = ?", String.class, reservationId))
                .containsExactly("CONFIRMED>CANCELED:CUSTOMER:일정이 생겼어요");
        assertThat(dedupeKeys(reservationId)).contains("RESERVATION:" + reservationId + ":CANCELED:OWNER:WEB_PUSH");

        cancelAs(customer.token(), "/api/v1/customer/reservations/" + reservationId + "/cancel",
                "{\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.alreadyCanceled").value(true))
                .andExpect(jsonPath("$.result.closedChangeIds", hasSize(0)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation_history WHERE reservation_id = ?",
                Integer.class, reservationId)).isEqualTo(1);
        request(customer, reservationId, "{\"requestNote\":\"취소 철회\",\"baseVersion\":1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_CHANGEABLE"))
                .andExpect(jsonPath("$.result.status").value("CANCELED"));

        // 사장님 취소 - 사유 필수, 고객에게 문자
        long ownerCanceled = reservation(customer, monday.plusDays(1), 10);
        cancelAs(ownerToken, "/api/v1/owner/reservations/" + ownerCanceled + "/cancel", "{\"version\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("reason"));
        cancelAs(ownerToken, "/api/v1/owner/reservations/" + ownerCanceled + "/cancel",
                "{\"version\":0,\"reason\":\"자재 입고 지연\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("CANCELED"));
        assertThat(jdbc.queryForObject("SELECT reason FROM reservation_history WHERE reservation_id = ?",
                String.class, ownerCanceled)).isEqualTo("자재 입고 지연");
        assertThat(dedupeKeys(ownerCanceled)).containsExactly(
                "RESERVATION:" + ownerCanceled + ":CANCELED:CUSTOMER:SMS");

        // 작업 완료 예약 - 업체 연락처 안내
        long completed = reservation(customer, monday.plusDays(2), 10);
        jdbc.update("UPDATE reservation SET status = 'COMPLETED' WHERE id = ?", completed);
        String ownerPhone = jdbc.queryForObject("SELECT phone FROM owner WHERE id = ?", String.class, ownerId);
        cancelAs(customer.token(), "/api/v1/customer/reservations/" + completed + "/cancel", "{\"version\":0}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_COMPLETED"))
                .andExpect(jsonPath("$.result.contactPhone").value(ownerPhone));

        // 다른 번호 고객 - 있는지도 모름
        cancelAs(customer().token(), "/api/v1/customer/reservations/" + completed + "/cancel", "{\"version\":0}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("수락과 취소가 동시에 - 하나만 반영, 뒤의 요청은 RESERVATION_VERSION_CONFLICT, 취소가 되살아나지 않음")
    void concurrentAcceptAndCancel() throws Exception {
        Customer customer = customer();
        long reservationId = reservation(customer, monday, 10);
        long changeId = proposedChange(customer, reservationId, time(monday, "14:00"));

        List<MockHttpServletResponse> responses = runTogether(
                () -> accept(customer, reservationId, changeId, 0).andReturn().getResponse(),
                () -> cancelAs(customer.token(), "/api/v1/customer/reservations/" + reservationId + "/cancel",
                        "{\"version\":0}").andReturn().getResponse());

        assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 409);
        MockHttpServletResponse loser = responses.get(0).getStatus() == 409 ? responses.get(0) : responses.get(1);
        assertThat((String) JsonPath.read(loser.getContentAsString(), "$.code"))
                .isEqualTo("RESERVATION_VERSION_CONFLICT");
        Map<String, Object> row = jdbc.queryForMap("SELECT r.status AS reservation, r.version, c.status AS change "
                + "FROM reservation r JOIN reservation_change c ON c.reservation_id = r.id WHERE c.id = ?", changeId);
        assertThat(row.get("version")).isEqualTo(1);
        if ("CANCELED".equals(row.get("reservation"))) {
            assertThat(row.get("change")).isEqualTo("CLOSED");
        } else {
            assertThat(row).containsEntry("reservation", "CONFIRMED").containsEntry("change", "APPLIED");
        }
    }

    // ---------- 도우미 ----------

    private void assertProposalBlocked(long reservationId, String startAt, String reason) throws Exception {
        propose(reservationId, proposal(null, startAt, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_UNAVAILABLE"))
                .andExpect(jsonPath("$.result.reason").value(reason));
    }

    // 고객 요청 → 사장님 제안까지 (PROPOSED)
    private long proposedChange(Customer customer, long reservationId, String startAt) throws Exception {
        long changeId = changeId(request(customer, reservationId, "{\"requestNote\":\"시간 바꿔 주세요\","
                + "\"baseVersion\":0}").andExpect(status().isCreated()));
        propose(reservationId, proposal(changeId, startAt, 0)).andExpect(status().isOk());
        return changeId;
    }

    private static String proposal(Long changeId, String startAt, int baseVersion) {
        return "{" + (changeId == null ? "" : "\"changeId\":" + changeId + ",") + "\"proposedStartAt\":\"" + startAt
                + "\",\"baseVersion\":" + baseVersion + "}";
    }

    private Customer customer() {
        String phone = accounts.newPhone();
        long customerId = jdbc.queryForObject("INSERT INTO customer (owner_id, name, phone) VALUES (?, '김고객', ?) "
                + "RETURNING id", Long.class, ownerId, phone);
        String token = accounts.token("CUSTOMER", ownerId, null, phone, "RESERVATION_LOOKUP",
                "CURRENT_TIMESTAMP + interval '1 day'");
        return new Customer(customerId, phone, token);
    }

    // 그 고객의 확정 예약 (출발지와 같은 좌표, 수리 60분)
    private long reservation(Customer customer, LocalDate date, int hour) {
        return jdbc.queryForObject("INSERT INTO reservation (owner_id, customer_id, start_at, end_at, address, lat, "
                + "lng, status, work_type_id, work_minutes, buffer_minutes, travel_time_minutes) "
                + "VALUES (?, ?, ?, ?, ?, 37.634, 126.832, 'CONFIRMED', ?, 60, 15, 0) RETURNING id", Long.class,
                ownerId, customer.customerId(), at(date, hour, 0), at(date, hour + 1, 0), ADDRESS, workTypeId);
    }

    private Map<String, Object> reservationRow(long reservationId) {
        return jdbc.queryForMap("SELECT version, address, start_at = ? AS start_ok FROM reservation WHERE id = ?",
                at(monday, 10, 0), reservationId);
    }

    private List<String> dedupeKeys(long reservationId) {
        return jdbc.queryForList("SELECT dedupe_key FROM notification WHERE ref_type = 'RESERVATION' AND ref_id = ? "
                + "ORDER BY id", String.class, reservationId);
    }

    private ResultActions request(Customer customer, long reservationId, String body) throws Exception {
        return postAs(customer.token(), "/api/v1/customer/reservations/" + reservationId + "/change-requests", body);
    }

    private ResultActions propose(long reservationId, String body) throws Exception {
        return postAs(ownerToken, "/api/v1/owner/reservations/" + reservationId + "/proposals", body);
    }

    private ResultActions accept(Customer customer, long reservationId, long changeId, int baseVersion)
            throws Exception {
        return postAs(customer.token(), "/api/v1/customer/reservations/" + reservationId + "/changes/" + changeId
                + "/accept", "{\"baseVersion\":" + baseVersion + "}");
    }

    private ResultActions reject(Customer customer, long reservationId, long changeId) throws Exception {
        return mockMvc.perform(post("/api/v1/customer/reservations/" + reservationId + "/changes/" + changeId
                + "/reject").header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.token()));
    }

    private ResultActions close(long changeId) throws Exception {
        return mockMvc.perform(post("/api/v1/owner/change-requests/" + changeId + "/close")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken));
    }

    private ResultActions cancelAs(String token, String path, String body) throws Exception {
        return postAs(token, path, body);
    }

    private ResultActions postAs(String token, String path, String body) throws Exception {
        return mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions getAs(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static long changeId(ResultActions actions) throws Exception {
        return ((Number) JsonPath.read(actions.andReturn().getResponse().getContentAsString(),
                "$.result.changeId")).longValue();
    }

    private List<MockHttpServletResponse> runTogether(Callable<MockHttpServletResponse> first,
                                                      Callable<MockHttpServletResponse> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (Callable<MockHttpServletResponse> call : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await();
            go.countDown();
            List<MockHttpServletResponse> responses = new ArrayList<>();
            for (Future<MockHttpServletResponse> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static OffsetDateTime at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atOffset(ZoneOffset.ofHours(9));
    }

    private static String time(LocalDate date, String hhmm) {
        return date + "T" + hhmm + ":00+09:00";
    }
}
