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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 사장님 수동 예약·차단 시간·일정 보기 (B-17·B-18·B-22·B-28, 명세 4.5·4.6·5.2, BE-12)
// 로컬 프로필이라 가짜 지도(FakeMapClient): 같은 좌표는 이동 0분, 다른 좌표는 직선거리로 5분 단위
@SpringBootTest
class OwnerScheduleFlowTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String ADDRESS = "경기 고양시 덕양구 화정로 1";
    private static final String FAR_ADDRESS = "경기 고양시 덕양구 ○○로 34";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private long ownerId;
    private String ownerToken;
    private long workTypeId;
    // 다음다음 주 월요일 - 지난 시각이 끼지 않게
    private LocalDate monday;

    // 평일 09:00~18:00, 토요일 휴무(일요일은 시간 없음), 점심 12~13시, 하루 3건, 여유시간 15분
    // 출발지 좌표 = ADDRESS 좌표, 수리 60분, 서비스 지역·자동 예약 설정 없음 (수동 예약은 보지 않음)
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
                + "is_active, auto_booking_allowed) VALUES (?, 'REPAIR', 60, true, false) RETURNING id", Long.class,
                ownerId);
        monday = LocalDate.now(KST).with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(1);
    }

    @AfterEach
    void tearDown() {
        accounts.cleanUp();
    }

    @Test
    @DisplayName("수동 예약 - 201 CONFIRMED·is_manual, 고객 행·이름·이력·고객 문자, 같은 키는 200, 내용이 다르면 409")
    void manualReservation() throws Exception {
        String phone = accounts.newPhone();
        String dashed = phone.substring(0, 3) + "-" + phone.substring(3, 7) + "-" + phone.substring(7);
        String key = UUID.randomUUID().toString();
        String body = manual(dashed, "박고객", time(monday, "10:00"), false);

        long reservationId = reservationId(createManual(key, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.result.startAt").value(time(monday, "10:00")))
                .andExpect(jsonPath("$.result.endAt").value(time(monday, "11:00")))
                .andExpect(jsonPath("$.result.version").value(0))
                .andExpect(jsonPath("$.result.outsideBusinessHours").value(false))
                .andExpect(jsonPath("$.result.notifications", hasSize(1)))
                .andExpect(jsonPath("$.result.notifications[0].recipient").value("CUSTOMER"))
                .andExpect(jsonPath("$.result.notifications[0].channel").value("SMS")));
        assertThat(jdbc.queryForMap("SELECT r.is_manual, r.inquiry_id, r.status, r.work_minutes, r.buffer_minutes, "
                        + "r.travel_time_minutes, r.address, c.phone, c.name FROM reservation r "
                        + "JOIN customer c ON c.id = r.customer_id WHERE r.id = ?", reservationId))
                .containsEntry("is_manual", true)
                .containsEntry("inquiry_id", null)
                .containsEntry("status", "CONFIRMED")
                .containsEntry("work_minutes", 60)
                .containsEntry("buffer_minutes", 15)
                .containsEntry("travel_time_minutes", 0)
                .containsEntry("address", ADDRESS)
                .containsEntry("phone", phone)
                .containsEntry("name", "박고객");
        assertThat(jdbc.queryForList("SELECT coalesce(from_status, '-') || '>' || to_status || ':' || changed_by "
                + "FROM reservation_history WHERE reservation_id = ?", String.class, reservationId))
                .containsExactly("->CONFIRMED:OWNER");
        assertThat(jdbc.queryForList("SELECT dedupe_key FROM notification WHERE ref_type = 'RESERVATION' "
                + "AND ref_id = ?", String.class, reservationId))
                .containsExactly("RESERVATION:" + reservationId + ":CONFIRMED:CUSTOMER:SMS");

        // 같은 키·같은 요청(번호 표기만 다름) → 처음 결과 200, 새로 만들지 않음
        createManual(key.toUpperCase(), manual(phone, "박고객", time(monday, "10:00"), false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.reservationId").value((int) reservationId))
                .andExpect(jsonPath("$.result.notifications", hasSize(1)));
        createManual(key, manual(phone, "박고객", time(monday, "14:00"), false))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mockMvc.perform(post("/api/v1/owner/reservations").header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("Idempotency-Key"));
        assertThat(manualCount()).isEqualTo(1);

        // 같은 번호로 다시 - 고객 행은 하나
        createManual(UUID.randomUUID().toString(), manual(phone, null, time(monday, "14:00"), false))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer WHERE owner_id = ? AND phone = ?",
                Integer.class, ownerId, phone)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name FROM customer WHERE owner_id = ? AND phone = ?", String.class,
                ownerId, phone)).isEqualTo("박고객");
    }

    @Test
    @DisplayName("자정 가까이의 수동 예약 - 자정에 끝나는 방문을 받고, 자정 너머 가까운 앞뒤 방문과의 여유·이동시간도 봄")
    void manualNearMidnight() throws Exception {
        // 화요일 00:10 방문이 이미 있음 (같은 좌표, 이동 0분·여유 15분)
        long customerId = jdbc.queryForObject("INSERT INTO customer (owner_id, name, phone) VALUES (?, '김고객', ?) "
                + "RETURNING id", Long.class, ownerId, accounts.newPhone());
        jdbc.update("INSERT INTO reservation (owner_id, customer_id, start_at, end_at, address, lat, lng, status, "
                + "work_type_id, work_minutes, is_manual) VALUES (?, ?, ?, ?, ?, 37.634, 126.832, 'CONFIRMED', ?, 60, "
                + "true)", ownerId, customerId, at(monday.plusDays(1), 0, 10), at(monday.plusDays(1), 1, 10), ADDRESS,
                workTypeId);

        // 월요일 23:00~24:00 - 끝난 뒤 여유 15분이면 00:15라 다음 날 00:10 방문에 못 감 (전에는 그날 일정만 봐서 통과)
        assertManualBlocked(time(monday, "23:00"), true, "TRAVEL_CONFLICT");
        // 22:30~23:30은 23:45에 갈 수 있음
        createManual(UUID.randomUUID().toString(), manual(accounts.newPhone(), null, time(monday, "22:30"), true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.outsideBusinessHours").value(true));

        // 수요일 23:00~24:00 - 자정에 끝나는 방문도 받음 (전에는 하루 끝을 23:59:59로 봐서 영업시간 밖)
        long late = reservationId(createManual(UUID.randomUUID().toString(), manual(accounts.newPhone(), null,
                time(monday.plusDays(2), "23:00"), true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.endAt").value(time(monday.plusDays(3), "00:00")))
                .andExpect(jsonPath("$.result.outsideBusinessHours").value(true)));
        assertThat(jdbc.queryForObject("SELECT outside_business_hours FROM reservation WHERE id = ?", Boolean.class,
                late)).isTrue();
        // 자정을 넘기는 방문은 받지 않음
        assertManualBlocked(time(monday.plusDays(4), "23:30"), true, "OUTSIDE_BUSINESS_HOURS");
        // 목요일 00:05 - 전날 24:00에 끝난 방문 뒤 여유 15분이 안 됨, 00:15는 됨
        assertManualBlocked(time(monday.plusDays(3), "00:05"), true, "TRAVEL_CONFLICT");
        createManual(UUID.randomUUID().toString(), manual(accounts.newPhone(), null,
                time(monday.plusDays(3), "00:15"), true))
                .andExpect(status().isCreated());
        // 영업시간 안의 방문은 전날 저녁·다음 날 아침 방문과 멀어 그대로 (그날 일정만 봄)
        createManual(UUID.randomUUID().toString(), manual(accounts.newPhone(), null,
                time(monday.plusDays(3), "09:00"), false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.outsideBusinessHours").value(false));
    }

    @Test
    @DisplayName("수동 예약 다시 보내기 - 영업시간 밖 여부는 등록할 때 값 그대로, 같은 키를 동시에 보내도 하나만 만들고 둘 다 성공")
    void manualReplay() throws Exception {
        // 영업시간 안에 등록 → 그 뒤 영업시간이 12시부터로 바뀌어도 다시 보낸 결과는 처음과 같음
        String key = UUID.randomUUID().toString();
        String body = manual(accounts.newPhone(), null, time(monday, "10:00"), false);
        createManual(key, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.outsideBusinessHours").value(false));
        jdbc.update("UPDATE owner_business_hour SET open_time = '12:00' WHERE owner_id = ? AND day_of_week = 1",
                ownerId);
        createManual(key, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.outsideBusinessHours").value(false));

        // 같은 키의 같은 요청 둘이 동시에 - 뒤 요청이 앞 요청이 잡은 시간을 '그 시간 참'(409)으로 보지 않고 같은 예약으로 답함
        for (int round = 0; round < 5; round++) {
            String sameKey = UUID.randomUUID().toString();
            String same = manual(accounts.newPhone(), null, time(monday.plusDays(1 + round % 4), "15:00"), false);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch go = new CountDownLatch(1);
                List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    futures.add(pool.submit(() -> {
                        go.await();
                        return createManual(sameKey, same).andReturn().getResponse();
                    }));
                }
                go.countDown();
                List<Integer> statuses = new ArrayList<>();
                List<Long> ids = new ArrayList<>();
                for (Future<MockHttpServletResponse> future : futures) {
                    MockHttpServletResponse response = future.get(30, TimeUnit.SECONDS);
                    statuses.add(response.getStatus());
                    assertThat(response.getStatus()).as(response.getContentAsString()).isIn(200, 201);
                    ids.add(((Number) JsonPath.read(response.getContentAsString(), "$.result.reservationId"))
                            .longValue());
                }
                assertThat(statuses).contains(201);
                assertThat(ids.get(0)).isEqualTo(ids.get(1));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation WHERE idempotency_key = ?",
                        Integer.class, sameKey)).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
            jdbc.update("DELETE FROM notification WHERE owner_id = ? AND ref_type = 'RESERVATION' AND ref_id IN "
                    + "(SELECT id FROM reservation WHERE idempotency_key = ?)", ownerId, sameKey);
            jdbc.update("DELETE FROM reservation_history WHERE reservation_id IN (SELECT id FROM reservation "
                    + "WHERE idempotency_key = ?)", sameKey);
            jdbc.update("DELETE FROM reservation WHERE idempotency_key = ?", sameKey);
        }
    }

    @Test
    @DisplayName("수동 예약 검사 - 영업시간 밖만 확인 후 등록, 점심·겹침·지난 시각·번호·작업 유형은 막음")
    void manualChecks() throws Exception {
        createManual(UUID.randomUUID().toString(), manual(accounts.newPhone(), null, time(monday, "10:00"), false))
                .andExpect(status().isCreated());

        assertManualBlocked(time(monday, "10:30"), false, "OVERLAP");
        assertManualBlocked(time(monday, "12:00"), true, "LUNCH_BREAK");
        assertManualBlocked(time(monday, "18:00"), false, "OUTSIDE_BUSINESS_HOURS");
        assertManualBlocked(time(LocalDate.now(KST).minusDays(1), "10:00"), true, "PAST");

        // 영업시간 밖 - 경고 확인 뒤 다시 보내면 등록 (같은 키를 다시 보내도 경고 여부 그대로)
        String key = UUID.randomUUID().toString();
        String late = manual(accounts.newPhone(), null, time(monday, "18:00"), true);
        createManual(key, late)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.outsideBusinessHours").value(true))
                .andExpect(jsonPath("$.result.endAt").value(time(monday, "19:00")));
        createManual(key, late)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.outsideBusinessHours").value(true));
        // 토요일 휴무도 같은 경고
        createManual(UUID.randomUUID().toString(), manual(accounts.newPhone(), null,
                time(monday.plusDays(5), "10:00"), true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.outsideBusinessHours").value(true));

        createManual(UUID.randomUUID().toString(), manual("02-123-4567", null, time(monday, "15:00"), false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("customerPhone"));
        createManual(UUID.randomUUID().toString(), manual(accounts.newPhone(), null, monday + "T15:00:30+09:00",
                false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("startAt"));
        long otherOwner = accounts.approvedOwner(accounts.newPhone());
        long otherWorkType = jdbc.queryForObject("INSERT INTO owner_work_type (owner_id, work_category, "
                + "duration_minutes, is_active) VALUES (?, 'REPLACEMENT', 120, true) RETURNING id", Long.class,
                otherOwner);
        // 꺼 둔 작업 유형
        long inactive = jdbc.queryForObject("INSERT INTO owner_work_type (owner_id, work_category, duration_minutes, "
                + "is_active) VALUES (?, 'INSPECTION', 30, false) RETURNING id", Long.class, ownerId);
        createManual(UUID.randomUUID().toString(), manual(accounts.newPhone(), null, time(monday, "15:00"), false)
                .replace("\"workTypeId\":" + workTypeId, "\"workTypeId\":" + inactive))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("workTypeId"));
        createManual(UUID.randomUUID().toString(), manual(accounts.newPhone(), null, time(monday, "15:00"), false)
                .replace("\"workTypeId\":" + workTypeId, "\"workTypeId\":" + otherWorkType))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("workTypeId"));
        createManual(UUID.randomUUID().toString(), "{\"customerPhone\":\"" + accounts.newPhone() + "\","
                + "\"address\":\"" + ADDRESS + "\",\"workTypeId\":" + workTypeId + ",\"startAt\":\""
                + time(monday, "15:00") + "\"}")
                .andExpect(status().isBadRequest());
        assertThat(manualCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("차단 시간 - 등록·목록·수정·해제, 확정 예약과 겹치면 409 + 겹친 예약, 확인하면 저장, 막힌 시간은 수동 예약 불가")
    void blocks() throws Exception {
        long reservationId = reservationId(createManual(UUID.randomUUID().toString(),
                manual(accounts.newPhone(), null, time(monday, "10:00"), false)).andExpect(status().isCreated()));

        long blockId = blockId(sendBlock(post("/api/v1/owner/blocks"), block(time(monday, "14:00"),
                time(monday, "15:30"), "병원", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.startAt").value(time(monday, "14:00")))
                .andExpect(jsonPath("$.result.endAt").value(time(monday, "15:30")))
                .andExpect(jsonPath("$.result.reason").value("병원")));
        assertManualBlocked(time(monday, "15:00"), false, "BLOCKED");

        sendBlock(post("/api/v1/owner/blocks"), block(time(monday, "10:30"), time(monday, "11:30"), null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BLOCK_CONFLICTS_RESERVATION"))
                .andExpect(jsonPath("$.result.reservations[0].reservationId").value((int) reservationId));
        long acknowledged = blockId(sendBlock(post("/api/v1/owner/blocks"), block(time(monday, "10:30"),
                time(monday, "11:30"), null, true)).andExpect(status().isCreated()));
        // 겹친 예약은 그대로
        assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservationId))
                .isEqualTo("CONFIRMED");

        getAs(ownerToken, "/api/v1/owner/blocks?from=" + monday + "&to=" + monday)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[*].blockId").value(contains((int) acknowledged, (int) blockId)));
        getAs(ownerToken, "/api/v1/owner/blocks?from=" + monday.plusDays(1) + "&to=" + monday.plusDays(2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result", hasSize(0)));
        getAs(ownerToken, "/api/v1/owner/blocks?from=" + monday + "&to=" + monday.plusDays(62))
                .andExpect(status().isBadRequest());

        sendBlock(put("/api/v1/owner/blocks/" + blockId), block(time(monday, "16:00"), time(monday, "17:00"),
                "병원 시간 바뀜", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.startAt").value(time(monday, "16:00")))
                .andExpect(jsonPath("$.result.reason").value("병원 시간 바뀜"));
        sendBlock(put("/api/v1/owner/blocks/" + blockId), block(time(monday, "16:00"), time(monday, "16:00"),
                null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("endAt"));
        // 다른 업체의 차단 시간은 없는 것
        long otherOwner = accounts.approvedOwner(accounts.newPhone());
        mockMvc.perform(delete("/api/v1/owner/blocks/" + blockId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accounts.ownerToken(otherOwner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BLOCK_NOT_FOUND"));
        mockMvc.perform(delete("/api/v1/owner/blocks/" + blockId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/owner/blocks/" + blockId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
        // 해제한 시간은 다시 예약 가능
        createManual(UUID.randomUUID().toString(), manual(accounts.newPhone(), null, time(monday, "16:00"), false))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("일정 보기 - 날짜별 영업시간·차단·방문, 방문마다 이동/여유/작업시간과 확인 필요, 진행 중 변경, 취소 제외")
    void schedule() throws Exception {
        long first = reservation(monday, 10, "37.634", "126.832", "CONFIRMED");
        long second = reservation(monday, 13, "37.650", "126.850", "CONFIRMED");
        // 좌표가 없는 예약 - 이동시간을 모름
        long third = reservation(monday, 16, null, null, "COMPLETED");
        reservation(monday, 15, "37.634", "126.832", "CANCELED");
        jdbc.update("INSERT INTO owner_block (owner_id, start_at, end_at, reason) VALUES (?, ?, ?, '병원')", ownerId,
                at(monday, 14, 30), at(monday, 15, 0));
        long changeId = jdbc.queryForObject("INSERT INTO reservation_change (reservation_id, change_type, requested_by, "
                + "status, request_note, prev_start_at, prev_end_at, base_version) "
                + "VALUES (?, 'CHANGE', 'CUSTOMER', 'REQUESTED', '오후로', ?, ?, 0) RETURNING id", Long.class, first,
                at(monday, 10, 0), at(monday, 11, 0));

        getAs(ownerToken, "/api/v1/owner/schedule?from=" + monday + "&to=" + monday.plusDays(5))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.from").value(monday.toString()))
                .andExpect(jsonPath("$.result.days", hasSize(6)))
                .andExpect(jsonPath("$.result.days[0].date").value(monday.toString()))
                .andExpect(jsonPath("$.result.days[0].closed").value(false))
                .andExpect(jsonPath("$.result.days[0].maxDailyVisits").value(3))
                .andExpect(jsonPath("$.result.days[0].visitCount").value(3))
                .andExpect(jsonPath("$.result.days[0].needsCheck").value(true))
                .andExpect(jsonPath("$.result.days[0].blocks", hasSize(1)))
                .andExpect(jsonPath("$.result.days[0].blocks[0].reason").value("병원"))
                .andExpect(jsonPath("$.result.days[0].visits[*].reservationId").value(contains(
                        (int) first, (int) second, (int) third)))
                // 첫 방문 - 출발지에서 (같은 좌표 0분), 여유시간은 앞 방문이 없어 null
                .andExpect(jsonPath("$.result.days[0].visits[0].travelStatus").value("OK"))
                .andExpect(jsonPath("$.result.days[0].visits[0].travelMinutes").value(0))
                .andExpect(jsonPath("$.result.days[0].visits[0].bufferMinutes").doesNotExist())
                .andExpect(jsonPath("$.result.days[0].visits[0].workMinutes").value(60))
                .andExpect(jsonPath("$.result.days[0].visits[0].customerName").value("김고객"))
                .andExpect(jsonPath("$.result.days[0].visits[0].workCategory").value("REPAIR"))
                .andExpect(jsonPath("$.result.days[0].visits[0].activeChange.changeId").value((int) changeId))
                .andExpect(jsonPath("$.result.days[0].visits[0].activeChange.status").value("REQUESTED"))
                // 둘째 방문 - 앞 방문지에서 오는 이동시간 + 여유시간 15분, 남는 시간
                .andExpect(jsonPath("$.result.days[0].visits[1].previousReservationId").value((int) first))
                .andExpect(jsonPath("$.result.days[0].visits[1].travelMinutes").value(greaterThan(0)))
                .andExpect(jsonPath("$.result.days[0].visits[1].bufferMinutes").value(15))
                .andExpect(jsonPath("$.result.days[0].visits[1].slackMinutes").value(greaterThan(0)))
                .andExpect(jsonPath("$.result.days[0].visits[1].travelStatus").value("OK"))
                .andExpect(jsonPath("$.result.days[0].visits[1].activeChange").doesNotExist())
                // 셋째 방문 - 좌표가 없어 이동시간을 모름 (완료 예약도 그날 동선이라 보임)
                .andExpect(jsonPath("$.result.days[0].visits[2].status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.days[0].visits[2].travelStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.result.days[0].visits[2].travelMinutes").doesNotExist())
                .andExpect(jsonPath("$.result.days[1].visits", hasSize(0)))
                .andExpect(jsonPath("$.result.days[1].needsCheck").value(false))
                // 토요일 휴무
                .andExpect(jsonPath("$.result.days[5].closed").value(true))
                .andExpect(jsonPath("$.result.days[5].openTime").doesNotExist());

        getAs(ownerToken, "/api/v1/owner/schedule?from=" + monday + "&to=" + monday.plusDays(31))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("to"));
        getAs(ownerToken, "/api/v1/owner/schedule?from=" + monday + "&to=" + monday.minusDays(1))
                .andExpect(status().isBadRequest());
        getAs(ownerToken, "/api/v1/owner/schedule?from=" + monday)
                .andExpect(status().isBadRequest());
        // 다른 업체 일정에는 안 보임
        long otherOwner = accounts.approvedOwner(accounts.newPhone());
        getAs(accounts.ownerToken(otherOwner), "/api/v1/owner/schedule?from=" + monday + "&to=" + monday)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.days[0].visits", hasSize(0)))
                .andExpect(jsonPath("$.result.days[0].closed").value(true));
    }

    // ---------- 도우미 ----------

    private void assertManualBlocked(String startAt, boolean acknowledge, String reason) throws Exception {
        createManual(UUID.randomUUID().toString(), manual(accounts.newPhone(), null, startAt, acknowledge))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_UNAVAILABLE"))
                .andExpect(jsonPath("$.result.reason").value(reason));
    }

    private String manual(String phone, String name, String startAt, boolean acknowledgeOutsideHours) {
        return "{\"customerPhone\":\"" + phone + "\"," + (name == null ? "" : "\"customerName\":\"" + name + "\",")
                + "\"address\":\"" + ADDRESS + "\",\"lat\":37.634,\"lng\":126.832,\"workTypeId\":" + workTypeId
                + ",\"startAt\":\"" + startAt + "\",\"acknowledgeOutsideHours\":" + acknowledgeOutsideHours + "}";
    }

    private static String block(String startAt, String endAt, String reason, Boolean acknowledge) {
        return "{\"startAt\":\"" + startAt + "\",\"endAt\":\"" + endAt + "\""
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
                + (acknowledge == null ? "" : ",\"acknowledgeConflicts\":" + acknowledge) + "}";
    }

    private ResultActions createManual(String key, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/owner/reservations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions sendBlock(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                    String body) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions getAs(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    // 고객이 있는 예약 (lat·lng가 null이면 좌표 없음)
    private long reservation(LocalDate date, int hour, String lat, String lng, String status) {
        long customerId = jdbc.queryForObject("INSERT INTO customer (owner_id, name, phone) VALUES (?, '김고객', ?) "
                + "RETURNING id", Long.class, ownerId, accounts.newPhone());
        return jdbc.queryForObject("INSERT INTO reservation (owner_id, customer_id, start_at, end_at, address, lat, "
                + "lng, status, work_type_id, work_minutes) VALUES (?, ?, ?, ?, ?, CAST(? AS numeric), "
                + "CAST(? AS numeric), ?, ?, 60) RETURNING id", Long.class, ownerId, customerId, at(date, hour, 0),
                at(date, hour + 1, 0), "37.634".equals(lat) ? ADDRESS : FAR_ADDRESS, lat, lng, status, workTypeId);
    }

    private int manualCount() {
        return jdbc.queryForObject("SELECT count(*) FROM reservation WHERE owner_id = ? AND is_manual", Integer.class,
                ownerId);
    }

    private static long reservationId(ResultActions actions) throws Exception {
        return ((Number) JsonPath.read(actions.andReturn().getResponse().getContentAsString(),
                "$.result.reservationId")).longValue();
    }

    private static long blockId(ResultActions actions) throws Exception {
        return ((Number) JsonPath.read(actions.andReturn().getResponse().getContentAsString(),
                "$.result.blockId")).longValue();
    }

    private static OffsetDateTime at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atOffset(ZoneOffset.ofHours(9));
    }

    private static String time(LocalDate date, String hhmm) {
        return date + "T" + hhmm + ":00+09:00";
    }
}
