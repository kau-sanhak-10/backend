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
import java.util.List;
import java.util.function.LongConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 방문 가능 시간 (C-12, 명세 5.2, BE-09) - 후보 계산과 자동 예약 불가 시 사장님 확인
// 로컬 프로필이라 가짜 지도(FakeMapClient): 같은 좌표는 이동 0분, 서비스 지역을 정했으면 지역 안
@SpringBootTest
class AvailableSlotsFlowTest {

    private static final String ALL_CONSENTS = """
            {"consents":[{"type":"PRIVACY_REQUIRED","termsVersion":"2026-10-v1"},
                         {"type":"PHOTO","termsVersion":"2026-10-v1"}]}""";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String ADDRESS = "경기 고양시 덕양구 화정로 1";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private long ownerId;
    private long workTypeId;
    // 다음다음 주 월요일 - 지난 시각이 끼지 않게
    private LocalDate monday;

    private record Session(long inquiryId, String token) {

        String slots(String query) {
            return "/api/v1/customer/inquiries/" + inquiryId + "/available-slots" + query;
        }
    }

    // 평일 09:00~18:00, 토요일 휴무, 일요일은 영업시간 없음, 점심 12~13시, 하루 2건, 여유시간 15분
    // 출발지·고객 주소가 같은 좌표 (가짜 지도 이동 0분), 수리 60분 자동 예약 허용, 서비스 지역 1곳
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        accounts = new TestAccounts(jdbc);
        ownerId = accounts.approvedOwner(accounts.newPhone());
        jdbc.update("UPDATE owner SET address = ?, lat = 37.634, lng = 126.832, lunch_start = '12:00', "
                + "lunch_end = '13:00', max_daily_visits = 2, buffer_minutes = 15, auto_booking_enabled = true "
                + "WHERE id = ?", ADDRESS, ownerId);
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
    @DisplayName("후보 - 영업시간 안 30분 간격, 점심·차단·기존 예약·앞뒤 여유시간 빼고, 하루 한도가 차면 그 이유")
    void slots() throws Exception {
        Session session = readyInquiry();
        LocalDate tuesday = monday.plusDays(1);
        LocalDate wednesday = monday.plusDays(2);
        reservation(tuesday, 10, "CONFIRMED", true);
        jdbc.update("INSERT INTO owner_block (owner_id, start_at, end_at, reason) VALUES (?, ?, ?, '개인 일정')",
                ownerId, at(tuesday, 15, 0), at(tuesday, 16, 0));
        reservation(wednesday, 9, "CONFIRMED", true);
        reservation(wednesday, 14, "COMPLETED", true);
        reservation(wednesday, 16, "CANCELED", true);

        getAs(session.token(), session.slots("?from=" + monday + "&days=3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.workMinutes").value(60))
                .andExpect(jsonPath("$.result.days", hasSize(3)))
                .andExpect(jsonPath("$.result.days[0].date").value(monday.toString()))
                .andExpect(jsonPath("$.result.days[0].slots[*].startAt").value(contains(times(monday,
                        "09:00", "09:30", "10:00", "10:30", "11:00", "13:00", "13:30", "14:00", "14:30", "15:00",
                        "15:30", "16:00", "16:30", "17:00").toArray())))
                .andExpect(jsonPath("$.result.days[0].slots[0].endAt").value(time(monday, "10:00")))
                .andExpect(jsonPath("$.result.days[0].emptyReason").doesNotExist())
                // 10~11시 예약: 앞뒤로 여유시간 15분 / 15~16시 차단
                .andExpect(jsonPath("$.result.days[1].slots[*].startAt").value(contains(times(tuesday,
                        "13:00", "13:30", "14:00", "16:00", "16:30", "17:00").toArray())))
                // 취소 예약은 세지 않고 완료 예약은 셈
                .andExpect(jsonPath("$.result.days[2].slots", empty()))
                .andExpect(jsonPath("$.result.days[2].emptyReason").value("DAILY_LIMIT_REACHED"));

        assertThat(inquiryStatus(session)).isEqualTo("SCHEDULING");
        assertThat(notifications(session)).isZero();
    }

    @Test
    @DisplayName("휴무·영업시간 없는 요일은 영업시간 밖, from 없으면 오늘부터이고 지난 시각은 빠짐")
    void closedDaysAndToday() throws Exception {
        Session session = readyInquiry();
        LocalDate saturday = monday.minusDays(2);

        getAs(session.token(), session.slots("?from=" + saturday + "&days=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.days[0].slots", empty()))
                .andExpect(jsonPath("$.result.days[0].emptyReason").value("OUTSIDE_BUSINESS_HOURS"))
                .andExpect(jsonPath("$.result.days[1].emptyReason").value("OUTSIDE_BUSINESS_HOURS"));

        String body = getAs(session.token(), session.slots("?days=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.days[0].date").value(LocalDate.now(KST).toString()))
                .andReturn().getResponse().getContentAsString();
        List<String> starts = JsonPath.read(body, "$.result.days[0].slots[*].startAt");
        OffsetDateTime now = OffsetDateTime.now(KST);
        assertThat(starts).allSatisfy(start -> assertThat(OffsetDateTime.parse(start)).isAfter(now));
    }

    @Test
    @DisplayName("업체 조건 - 자동 예약 꺼짐·신규 접수 중지·작업 자동 예약 미허용·비활성 작업이면 422, 사장님 확인 + 알림 1")
    void ownerConditions() throws Exception {
        assertAskedOwner("AUTO_BOOKING_DISABLED",
                id -> jdbc.update("UPDATE owner SET auto_booking_enabled = false WHERE id = ?", ownerId));
        jdbc.update("UPDATE owner SET auto_booking_enabled = true WHERE id = ?", ownerId);

        assertAskedOwner("AUTO_BOOKING_DISABLED",
                id -> jdbc.update("UPDATE owner SET intake_paused = true WHERE id = ?", ownerId));
        jdbc.update("UPDATE owner SET intake_paused = false WHERE id = ?", ownerId);

        assertAskedOwner("AUTO_BOOKING_DISABLED",
                id -> jdbc.update("UPDATE owner_work_type SET auto_booking_allowed = false WHERE id = ?", workTypeId));
        assertAskedOwner("WORK_TYPE_NOT_CONFIRMED",
                id -> jdbc.update("UPDATE owner_work_type SET is_active = false WHERE id = ?", workTypeId));
    }

    @Test
    @DisplayName("문의 조건 - 작업 유형 미확인·필수 항목(증상) 미확인·안전 신호·주소 좌표 없음이면 422, 사장님 확인")
    void inquiryConditions() throws Exception {
        assertAskedOwner("WORK_TYPE_NOT_CONFIRMED",
                id -> jdbc.update("UPDATE inquiry SET work_type_id = NULL WHERE id = ?", id));

        Session missing = assertAskedOwner("REQUIRED_INFO_UNCONFIRMED",
                id -> jdbc.update("DELETE FROM inquiry_field WHERE inquiry_id = ?", id));
        Session conflict = assertAskedOwner("REQUIRED_INFO_UNCONFIRMED",
                id -> jdbc.update("UPDATE inquiry_field SET status = 'UNCONFIRMED', field_value = NULL, "
                        + "unconfirmed_reason = 'CONFLICT' WHERE inquiry_id = ?", id));

        assertAskedOwner("SAFETY_CHECK_REQUIRED",
                id -> jdbc.update("UPDATE inquiry SET safety_flag = true WHERE id = ?", id));
        assertAskedOwner("OUT_OF_SERVICE_AREA",
                id -> jdbc.update("UPDATE inquiry SET lat = NULL, lng = NULL WHERE id = ?", id));

        // 필수 항목 결과는 항목별 이유 - 처음 막힌 뒤 다시 부르면 사장님 확인 중 409, 알림은 그대로 1
        for (Session session : List.of(missing, conflict)) {
            getAs(session.token(), session.slots(""))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("OWNER_HANDLING"));
            assertThat(notifications(session)).isEqualTo(1);
        }
        Session again = readyInquiry();
        jdbc.update("UPDATE inquiry_field SET status = 'UNCONFIRMED', field_value = NULL, "
                + "unconfirmed_reason = 'ASK_LIMIT_REACHED' WHERE inquiry_id = ?", again.inquiryId());
        getAs(again.token(), again.slots(""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.result.fields[0].fieldKey").value("symptom"))
                .andExpect(jsonPath("$.result.fields[0].reason").value("ASK_LIMIT_REACHED"));
    }

    @Test
    @DisplayName("서비스 지역을 안 정했거나 이동시간을 셀 수 없으면 (좌표 없는 기존 예약) 422, 시간을 임의로 만들지 않음")
    void areaAndTravel() throws Exception {
        assertAskedOwner("TRAVEL_TIME_UNAVAILABLE", id -> {
            jdbc.update("INSERT INTO reservation (owner_id, start_at, end_at, address, status) "
                    + "VALUES (?, ?, ?, '주소만 있음', 'CONFIRMED')", ownerId, at(monday, 10, 0), at(monday, 11, 0));
        });
        jdbc.update("DELETE FROM reservation WHERE owner_id = ?", ownerId);

        assertAskedOwner("OUT_OF_SERVICE_AREA",
                id -> jdbc.update("DELETE FROM owner_service_area WHERE owner_id = ?", ownerId));
    }

    @Test
    @DisplayName("예약할 수 없는 상담 - 종료 409·예약 있음 409·사장님 응대 중 409(상태 그대로)·범위 밖 days 400·남의 문의 404")
    void notSchedulable() throws Exception {
        Session closed = readyInquiry();
        jdbc.update("UPDATE inquiry SET status = 'CLOSED' WHERE id = ?", closed.inquiryId());
        getAs(closed.token(), closed.slots("")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INQUIRY_CLOSED"));

        Session booked = readyInquiry();
        long reservationId = jdbc.queryForObject("INSERT INTO reservation (inquiry_id, owner_id, start_at, end_at, "
                + "address, lat, lng, status) VALUES (?, ?, ?, ?, ?, 37.634, 126.832, 'CONFIRMED') RETURNING id",
                Long.class, booked.inquiryId(), ownerId, at(monday, 10, 0), at(monday, 11, 0), ADDRESS);
        getAs(booked.token(), booked.slots("")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.result.reservationId").value(reservationId));

        Session owner = readyInquiry();
        jdbc.update("UPDATE inquiry SET handler_mode = 'OWNER' WHERE id = ?", owner.inquiryId());
        getAs(owner.token(), owner.slots("")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNER_HANDLING"));
        assertThat(inquiryStatus(owner)).isEqualTo("SCHEDULING");
        assertThat(notifications(owner)).isZero();

        Session session = readyInquiry();
        for (String query : List.of("?days=0", "?days=15", "?from=2026-13-01")) {
            getAs(session.token(), session.slots(query)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        getAs(session.token(), closed.slots("")).andExpect(status().isNotFound());
    }

    // ---------- 도움 함수 ----------

    // 예약 준비가 끝난 상담 - 시간 선택 단계, 주소·좌표, 수리 작업, 증상 확인됨
    private Session readyInquiry() throws Exception {
        String body = mockMvc.perform(post("/api/v1/public/intake/" + accounts.linkToken(ownerId) + "/inquiries")
                        .contentType(MediaType.APPLICATION_JSON).content(ALL_CONSENTS))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Session session = new Session(((Number) JsonPath.read(body, "$.result.inquiryId")).longValue(),
                JsonPath.read(body, "$.result.inquiryToken"));
        jdbc.update("UPDATE inquiry SET address = ?, lat = 37.634, lng = 126.832, work_type_id = ?, "
                + "status = 'SCHEDULING' WHERE id = ?", ADDRESS, workTypeId, session.inquiryId());
        jdbc.update("INSERT INTO inquiry_field (inquiry_id, field_key, field_value, source, status) "
                + "VALUES (?, 'symptom', '온수가 안 나와요', 'CUSTOMER', 'CONFIRMED')", session.inquiryId());
        return session;
    }

    // 새 상담에 조건 하나를 깨뜨리고 조회 → 422 그 코드, 문의는 사장님 확인, 알림 1, version 오름
    private Session assertAskedOwner(String code, LongConsumer breakIt) throws Exception {
        Session session = readyInquiry();
        breakIt.accept(session.inquiryId());
        int versionBefore = version(session);
        getAs(session.token(), session.slots("?from=" + monday))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value(code));
        assertThat(inquiryStatus(session)).as(code).isEqualTo("OWNER_CHECK");
        assertThat(notifications(session)).as(code).isEqualTo(1);
        assertThat(version(session)).as(code).isEqualTo(versionBefore + 1);
        return session;
    }

    private void reservation(LocalDate date, int hour, String status, boolean withLocation) {
        jdbc.update("INSERT INTO reservation (owner_id, start_at, end_at, address, lat, lng, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)", ownerId, at(date, hour, 0), at(date, hour + 1, 0), ADDRESS,
                withLocation ? 37.634 : null, withLocation ? 126.832 : null, status);
    }

    private static OffsetDateTime at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atOffset(ZoneOffset.ofHours(9));
    }

    private static String time(LocalDate date, String hhmm) {
        return date + "T" + hhmm + ":00+09:00";
    }

    private static List<String> times(LocalDate date, String... hhmm) {
        return java.util.Arrays.stream(hhmm).map(value -> time(date, value)).toList();
    }

    private String inquiryStatus(Session session) {
        return jdbc.queryForObject("SELECT status FROM inquiry WHERE id = ?", String.class, session.inquiryId());
    }

    private int version(Session session) {
        return jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?", Integer.class, session.inquiryId());
    }

    private int notifications(Session session) {
        return jdbc.queryForObject("SELECT count(*) FROM notification WHERE ref_type = 'INQUIRY' AND ref_id = ? "
                + "AND event_type = 'NEEDS_CHECK'", Integer.class, session.inquiryId());
    }

    private ResultActions getAs(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }
}
