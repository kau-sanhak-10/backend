package com.ilro.backend.domain.reservation;

import com.ilro.backend.domain.reservation.service.ProposalExpiryJob;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 변경 이력 조회(C-17)와 제안 만료 작업 (명세 2.4·5.3, BE-16)
// 로컬 프로필이라 가짜 지도(FakeMapClient): 같은 좌표는 이동 0분, 서비스 지역을 정했으면 지역 안
@SpringBootTest
class ChangeHistoryFlowTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String ADDRESS = "경기 고양시 덕양구 화정로 1";
    private static final String NEW_ADDRESS = "경기 고양시 덕양구 ○○로 34 101동 1001호";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProposalExpiryJob expiryJob;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private long ownerId;
    private String ownerToken;
    private long workTypeId;
    // 다음다음 주 월요일 - 지난 시각이 끼지 않고, 제안 기한은 제안 후 24시간
    private LocalDate monday;

    private record Customer(long customerId, String phone, String token) {
    }

    // 평일 09:00~18:00, 점심 12~13시, 하루 3건, 여유시간 15분, 수리 60분, 출발지·예약 주소 같은 좌표, 서비스 지역 1곳
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
    @DisplayName("변경 이력 - 기존·제안 시간·주소와 기한, 끝난 변경까지 최근 순 / 상세의 진행 중 변경도 같은 모양")
    void history() throws Exception {
        Customer customer = customer();
        long reservationId = reservation(customer, monday, 10);
        String path = "/api/v1/customer/reservations/" + reservationId;

        getAs(customer.token(), path + "/changes")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.reservationId").value((int) reservationId))
                .andExpect(jsonPath("$.result.changes", hasSize(0)));

        // 1. 고객 요청 (시간·주소) → 사장님이 주소는 빼고 14시로 제안 → 고객 거절
        long first = changeId(postAs(customer.token(), path + "/change-requests", "{\"requestNote\":\"오후로요\","
                + "\"addressChange\":{\"address\":\"경기 고양시 덕양구 ○○로 34\",\"addressDetail\":\"101동 1001호\","
                + "\"lat\":37.65,\"lng\":126.85},\"baseVersion\":0}").andExpect(status().isCreated()));
        getAs(customer.token(), path + "/changes")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.changes", hasSize(1)))
                .andExpect(jsonPath("$.result.changes[0].changeId").value((int) first))
                .andExpect(jsonPath("$.result.changes[0].status").value("REQUESTED"))
                .andExpect(jsonPath("$.result.changes[0].requestedBy").value("CUSTOMER"))
                .andExpect(jsonPath("$.result.changes[0].requestNote").value("오후로요"))
                .andExpect(jsonPath("$.result.changes[0].previous.startAt").value(time(monday, "10:00")))
                .andExpect(jsonPath("$.result.changes[0].previous.endAt").value(time(monday, "11:00")))
                .andExpect(jsonPath("$.result.changes[0].previous.address").value(ADDRESS))
                .andExpect(jsonPath("$.result.changes[0].proposed.startAt").doesNotExist())
                .andExpect(jsonPath("$.result.changes[0].proposed.address").value(NEW_ADDRESS))
                .andExpect(jsonPath("$.result.changes[0].expiresAt").doesNotExist())
                .andExpect(jsonPath("$.result.changes[0].requestedAt", notNullValue()))
                .andExpect(jsonPath("$.result.changes[0].resolvedAt").doesNotExist());
        getAs(customer.token(), path)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.activeChange.changeId").value((int) first))
                .andExpect(jsonPath("$.result.activeChange.previous.startAt").value(time(monday, "10:00")))
                .andExpect(jsonPath("$.result.activeChange.proposed.address").value(NEW_ADDRESS));

        postAs(ownerToken, "/api/v1/owner/reservations/" + reservationId + "/proposals", "{\"changeId\":" + first
                + ",\"proposedStartAt\":\"" + time(monday, "14:00") + "\",\"applyAddressChange\":false,"
                + "\"baseVersion\":0}").andExpect(status().isOk());
        getAs(customer.token(), path)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.activeChange.status").value("PROPOSED"))
                .andExpect(jsonPath("$.result.activeChange.previous.startAt").value(time(monday, "10:00")))
                .andExpect(jsonPath("$.result.activeChange.previous.address").value(ADDRESS))
                .andExpect(jsonPath("$.result.activeChange.proposed.startAt").value(time(monday, "14:00")))
                .andExpect(jsonPath("$.result.activeChange.proposed.endAt").value(time(monday, "15:00")))
                // 주소는 바꾸지 않는 제안 - 바뀔 주소 없음
                .andExpect(jsonPath("$.result.activeChange.proposed.address").doesNotExist())
                .andExpect(jsonPath("$.result.activeChange.expiresAt", notNullValue()));
        postAs(customer.token(), path + "/changes/" + first + "/reject", "{}").andExpect(status().isOk());

        // 2. 고객 요청 → 사장님 닫기
        long second = changeId(postAs(customer.token(), path + "/change-requests",
                "{\"requestNote\":\"다음 주로\",\"baseVersion\":0}").andExpect(status().isCreated()));
        mockMvc.perform(post("/api/v1/owner/change-requests/" + second + "/close")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // 3. 업체 사유 제안 16시 → 고객 수락 (APPLIED, 예약 version 1)
        long third = changeId(postAs(ownerToken, "/api/v1/owner/reservations/" + reservationId + "/proposals",
                "{\"proposedStartAt\":\"" + time(monday, "16:00") + "\",\"baseVersion\":0}")
                .andExpect(status().isOk()));
        postAs(customer.token(), path + "/changes/" + third + "/accept", "{\"baseVersion\":0}")
                .andExpect(status().isOk());

        getAs(customer.token(), path + "/changes")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.changes[*].changeId").value(contains((int) third, (int) second,
                        (int) first)))
                .andExpect(jsonPath("$.result.changes[*].status").value(contains("APPLIED", "CLOSED", "REJECTED")))
                // 확정된 변경 - 바뀌기 전(10시)과 바뀐 값(16시)이 함께
                .andExpect(jsonPath("$.result.changes[0].requestedBy").value("OWNER"))
                .andExpect(jsonPath("$.result.changes[0].requestNote").doesNotExist())
                .andExpect(jsonPath("$.result.changes[0].previous.startAt").value(time(monday, "10:00")))
                .andExpect(jsonPath("$.result.changes[0].proposed.startAt").value(time(monday, "16:00")))
                .andExpect(jsonPath("$.result.changes[0].resolvedBy").value("CUSTOMER"))
                .andExpect(jsonPath("$.result.changes[0].resolvedAt", notNullValue()))
                .andExpect(jsonPath("$.result.changes[1].resolvedBy").value("OWNER"))
                .andExpect(jsonPath("$.result.changes[1].closeReason").value("OWNER_CLOSED"))
                .andExpect(jsonPath("$.result.changes[2].resolvedBy").value("CUSTOMER"))
                .andExpect(jsonPath("$.result.changes[2].proposed.startAt").value(time(monday, "14:00")));
        getAs(customer.token(), path)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value(1))
                .andExpect(jsonPath("$.result.activeChange").doesNotExist());

        // 권한 - 다른 번호의 고객 404, 사장님 토큰 403, 상담 복구 고객 토큰 403
        Customer other = customer();
        getAs(other.token(), path + "/changes")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        getAs(ownerToken, path + "/changes")
                .andExpect(status().isForbidden());
        String recoveryToken = accounts.token("CUSTOMER", ownerId, null, customer.phone(), "INQUIRY_ACCESS",
                "CURRENT_TIMESTAMP + interval '1 day'");
        getAs(recoveryToken, path + "/changes")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("VERIFICATION_PURPOSE_MISMATCH"));
    }

    @Test
    @DisplayName("제안 만료 작업 - 기한 지난 PROPOSED만 EXPIRED(SYSTEM), 예약 그대로, 그 뒤 수락 409·새 요청 가능")
    void expiryJob() throws Exception {
        Customer customer = customer();
        long dueReservation = reservation(customer, monday, 10);
        long liveReservation = reservation(customer, monday.plusDays(1), 10);
        long requestedReservation = reservation(customer, monday.plusDays(2), 10);
        long due = proposedChange(customer, dueReservation, time(monday, "14:00"));
        long live = proposedChange(customer, liveReservation, time(monday.plusDays(1), "14:00"));
        long requested = changeId(postAs(customer.token(), "/api/v1/customer/reservations/" + requestedReservation
                + "/change-requests", "{\"requestNote\":\"아무 때나\",\"baseVersion\":0}")
                .andExpect(status().isCreated()));
        jdbc.update("UPDATE reservation_change SET expires_at = CURRENT_TIMESTAMP - interval '1 minute' "
                + "WHERE id = ?", due);

        assertThat(expiryJob.expireDue()).isGreaterThanOrEqualTo(1);

        Map<String, Object> expired = jdbc.queryForMap("SELECT status, resolved_by, "
                + "resolved_at IS NOT NULL AS resolved FROM reservation_change WHERE id = ?", due);
        assertThat(expired).containsEntry("status", "EXPIRED").containsEntry("resolved_by", "SYSTEM")
                .containsEntry("resolved", true);
        assertThat(changeStatus(live)).isEqualTo("PROPOSED");
        assertThat(changeStatus(requested)).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForMap("SELECT version, status, start_at = ? AS start_ok FROM reservation WHERE id = ?",
                at(monday, 10), dueReservation))
                .containsEntry("version", 0).containsEntry("status", "CONFIRMED").containsEntry("start_ok", true);

        // 다시 돌려도 끝난 변경은 건드리지 않음
        expiryJob.expireDue();
        assertThat(changeStatus(due)).isEqualTo("EXPIRED");

        String path = "/api/v1/customer/reservations/" + dueReservation;
        postAs(customer.token(), path + "/changes/" + due + "/accept", "{\"baseVersion\":0}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANGE_ALREADY_RESOLVED"))
                .andExpect(jsonPath("$.result.change.status").value("EXPIRED"));
        getAs(customer.token(), path)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.activeChange").doesNotExist());
        getAs(customer.token(), path + "/changes")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.changes[0].status").value("EXPIRED"))
                .andExpect(jsonPath("$.result.changes[0].resolvedBy").value("SYSTEM"))
                .andExpect(jsonPath("$.result.changes[0].expiresAt", notNullValue()));
        // 만료 뒤에는 새 요청 가능 (진행 중 변경이 없음)
        postAs(customer.token(), path + "/change-requests", "{\"requestNote\":\"다시 잡아 주세요\",\"baseVersion\":0}")
                .andExpect(status().isCreated());
    }

    private String changeStatus(long changeId) {
        return jdbc.queryForObject("SELECT status FROM reservation_change WHERE id = ?", String.class, changeId);
    }

    // 고객 요청 → 사장님 제안까지 (PROPOSED)
    private long proposedChange(Customer customer, long reservationId, String startAt) throws Exception {
        long changeId = changeId(postAs(customer.token(), "/api/v1/customer/reservations/" + reservationId
                + "/change-requests", "{\"requestNote\":\"시간 바꿔 주세요\",\"baseVersion\":0}")
                .andExpect(status().isCreated()));
        postAs(ownerToken, "/api/v1/owner/reservations/" + reservationId + "/proposals", "{\"changeId\":" + changeId
                + ",\"proposedStartAt\":\"" + startAt + "\",\"baseVersion\":0}").andExpect(status().isOk());
        return changeId;
    }

    private Customer customer() {
        String phone = accounts.newPhone();
        long customerId = jdbc.queryForObject("INSERT INTO customer (owner_id, name, phone) VALUES (?, '김고객', ?) "
                + "RETURNING id", Long.class, ownerId, phone);
        String token = accounts.token("CUSTOMER", ownerId, null, phone, "RESERVATION_LOOKUP",
                "CURRENT_TIMESTAMP + interval '1 day'");
        return new Customer(customerId, phone, token);
    }

    private long reservation(Customer customer, LocalDate date, int hour) {
        return jdbc.queryForObject("INSERT INTO reservation (owner_id, customer_id, start_at, end_at, address, lat, "
                + "lng, status, work_type_id, work_minutes, buffer_minutes, travel_time_minutes) "
                + "VALUES (?, ?, ?, ?, ?, 37.634, 126.832, 'CONFIRMED', ?, 60, 15, 0) RETURNING id", Long.class,
                ownerId, customer.customerId(), at(date, hour), at(date, hour + 1), ADDRESS, workTypeId);
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

    private static OffsetDateTime at(LocalDate date, int hour) {
        return date.atTime(hour, 0).atOffset(ZoneOffset.ofHours(9));
    }

    private static String time(LocalDate date, String hhmm) {
        return date + "T" + hhmm + ":00+09:00";
    }
}
