package com.ilro.backend.domain.reservation;

import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.repository.OwnerRepository;
import com.ilro.backend.domain.reservation.exception.SlotUnavailableReason;
import com.ilro.backend.domain.reservation.service.MapLookup;
import com.ilro.backend.domain.reservation.service.SlotCheck;
import com.ilro.backend.domain.reservation.service.VisitScheduleService;
import com.ilro.backend.domain.reservation.service.VisitTimeline.DayPlan;
import com.ilro.backend.domain.reservation.service.VisitTimeline.PlannedVisit;
import com.ilro.backend.domain.reservation.service.VisitTimeline.TravelStatus;
import com.ilro.backend.infra.map.GeoPoint;
import com.ilro.backend.infra.map.MapClient;
import com.ilro.backend.infra.map.ServiceAreaMatch;
import com.ilro.backend.support.TestAccounts;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 이동시간 반영 일정 계산 (BE-10) - 테스트 지도 응답으로 확인 (실제 지도 연동 확인은 BE-15 뒤 MapClientContract)
// 지도는 구간별로 정한 분을 답하고 물은 구간을 기억하는 ScriptedMap (정하지 않은 구간은 20분)
@SpringBootTest
class TravelTimeFlowTest {

    private static final String ALL_CONSENTS = """
            {"consents":[{"type":"PRIVACY_REQUIRED","termsVersion":"2026-10-v1"},
                         {"type":"PHOTO","termsVersion":"2026-10-v1"}]}""";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String ADDRESS = "경기 고양시 덕양구 화정로 1";
    // 업체 출발지 · 고객 방문지 · 다른 예약 방문지
    private static final GeoPoint ORIGIN = point("37.600", "126.800");
    private static final GeoPoint CUSTOMER = point("37.634", "126.832");
    private static final GeoPoint OTHER = point("37.650", "126.850");

    @TestConfiguration
    static class MapConfig {

        @Bean
        @Primary
        ScriptedMap scriptedMap() {
            return new ScriptedMap();
        }
    }

    static class ScriptedMap implements MapClient {

        final Map<List<GeoPoint>, Integer> minutes = new HashMap<>();
        final List<List<GeoPoint>> asked = Collections.synchronizedList(new ArrayList<>());
        volatile boolean travelFails;
        volatile boolean areaFails;

        void reset() {
            minutes.clear();
            asked.clear();
            travelFails = false;
            areaFails = false;
        }

        @Override
        public OptionalInt carTravelMinutes(GeoPoint from, GeoPoint to) {
            asked.add(List.of(from, to));
            if (travelFails) {
                throw new IllegalStateException("지도 응답 시간 초과");
            }
            return OptionalInt.of(minutes.getOrDefault(List.of(from, to), 20));
        }

        @Override
        public ServiceAreaMatch matchServiceArea(GeoPoint point, List<String> regionCodes) {
            if (areaFails) {
                throw new IllegalStateException("지도 서버 오류");
            }
            return regionCodes.isEmpty() ? ServiceAreaMatch.OUTSIDE : ServiceAreaMatch.INSIDE;
        }
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScriptedMap map;

    @Autowired
    private MapLookup mapLookup;

    @Autowired
    private VisitScheduleService scheduleService;

    @Autowired
    private OwnerRepository ownerRepository;

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

    // 평일 09:00~18:00, 점심 12~13시, 하루 3건, 여유시간 15분, 출발지와 고객 방문지가 다른 곳
    @BeforeEach
    void setUp() {
        map.reset();
        mapLookup.clear();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        accounts = new TestAccounts(jdbc);
        ownerId = accounts.approvedOwner(accounts.newPhone());
        jdbc.update("UPDATE owner SET address = '경기 고양시 덕양구 출발지', lat = ?, lng = ?, lunch_start = '12:00', "
                + "lunch_end = '13:00', max_daily_visits = 3, buffer_minutes = 15, auto_booking_enabled = true "
                + "WHERE id = ?", ORIGIN.lat(), ORIGIN.lng(), ownerId);
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
    @DisplayName("후보 - 출발지·앞뒤 예약지에서 오는 이동시간 + 여유시간을 빼고, 다시 조회해도 같은 구간은 지도에 한 번만 물음")
    void slotsWithTravel() throws Exception {
        Session session = readyInquiry();
        // 13~14시 다른 곳 예약: 앞은 12:00 끝 + 15 + 20 = 12:35, 뒤는 14:00 + 15 + 20 = 14:35부터
        reservation(monday, 13, 0, 14, 0, OTHER, "CONFIRMED");
        map.minutes.put(List.of(ORIGIN, CUSTOMER), 25);
        List<String> expected = times(monday, "09:30", "10:00", "10:30", "11:00", "15:00", "15:30", "16:00",
                "16:30", "17:00");

        for (int call = 0; call < 2; call++) {
            getAs(session.token(), session.slots("?from=" + monday + "&days=1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.days[0].slots[*].startAt").value(contains(expected.toArray())));
        }
        assertThat(map.asked).as("출발지→고객, 고객→다른 예약지, 다른 예약지→고객 - 두 번째 조회는 기억한 값")
                .containsExactlyInAnyOrder(List.of(ORIGIN, CUSTOMER), List.of(CUSTOMER, OTHER),
                        List.of(OTHER, CUSTOMER));
        assertThat(inquiryStatus(session)).isEqualTo("SCHEDULING");
    }

    @Test
    @DisplayName("지도 오류 - 이동시간을 0분으로 만들지 않고 422 TRAVEL_TIME_UNAVAILABLE, 문의는 사장님 확인 + 알림 1")
    void travelMapError() throws Exception {
        Session session = readyInquiry();
        map.travelFails = true;

        getAs(session.token(), session.slots("?from=" + monday + "&days=1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TRAVEL_TIME_UNAVAILABLE"));
        assertThat(inquiryStatus(session)).isEqualTo("OWNER_CHECK");
        assertThat(notifications(session)).isEqualTo(1);
    }

    @Test
    @DisplayName("지역 판별 오류 - 지역 안으로 보지 않고 422 OUT_OF_SERVICE_AREA, 문의는 사장님 확인")
    void areaMapError() throws Exception {
        Session session = readyInquiry();
        map.areaFails = true;

        getAs(session.token(), session.slots("?from=" + monday + "&days=1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OUT_OF_SERVICE_AREA"));
        assertThat(inquiryStatus(session)).isEqualTo("OWNER_CHECK");
        assertThat(notifications(session)).isEqualTo(1);
    }

    @Test
    @DisplayName("한 시각 검사 - 예약 변경은 자기 예약을 빼고 검사, 좌표 없는 방문지는 이동시간 모름 (B-18·B-19·BE-11)")
    void checkOneTime() {
        long own = reservation(monday, 10, 0, 11, 0, CUSTOMER, "CONFIRMED");
        reservation(monday, 15, 0, 16, 0, OTHER, "CONFIRMED");
        Owner owner = ownerRepository.findById(ownerId).orElseThrow();

        SlotCheck withOwn = scheduleService.check(owner, at(monday, 10, 30).toInstant(), 60, CUSTOMER, null);
        assertThat(withOwn.reason()).isEqualTo(SlotUnavailableReason.OVERLAP);

        SlotCheck moved = scheduleService.check(owner, at(monday, 10, 30).toInstant(), 60, CUSTOMER, own);
        assertThat(moved.isAvailable()).isTrue();
        assertThat(moved.travelMinutes()).as("출발지→고객").isEqualTo(20);

        SlotCheck tooClose = scheduleService.check(owner, at(monday, 13, 30).toInstant(), 60, CUSTOMER, own);
        assertThat(tooClose.reason()).as("14:30 + 15 + 20 = 15:05").isEqualTo(SlotUnavailableReason.TRAVEL_CONFLICT);

        assertThat(scheduleService.check(owner, at(monday, 10, 30).toInstant(), 60, null, own).travelUnknown())
                .isTrue();
    }

    @Test
    @DisplayName("하루 일정 - 방문마다 이동·여유·작업시간을 나누고, 빠듯하거나 이동시간을 모르는 방문은 사장님 확인 필요")
    void dayPlan() {
        // 09:30~10:30 고객지 → 10:50~11:50 다른 곳 (10:30 + 15 + 20 = 11:05, 15분 모자람) → 14:00 좌표 없는 곳
        long first = reservation(monday, 9, 30, 10, 30, CUSTOMER, "CONFIRMED");
        long second = reservation(monday, 10, 50, 11, 50, OTHER, "CONFIRMED");
        long third = reservation(monday, 14, 0, 15, 0, null, "CONFIRMED");
        reservation(monday, 16, 0, 17, 0, OTHER, "CANCELED");
        map.minutes.put(List.of(ORIGIN, CUSTOMER), 25);
        Owner owner = ownerRepository.findById(ownerId).orElseThrow();

        List<DayPlan> plans = scheduleService.plan(owner, monday, monday.plusDays(2));

        assertThat(plans).extracting(DayPlan::date).containsExactly(monday, monday.plusDays(1));
        assertThat(plans.get(0).visits())
                .extracting(PlannedVisit::reservationId, PlannedVisit::workMinutes,
                        PlannedVisit::previousReservationId, PlannedVisit::travelMinutes,
                        PlannedVisit::bufferMinutes, PlannedVisit::slackMinutes, PlannedVisit::travelStatus)
                .containsExactly(
                        tuple(first, 60, null, 25, null, null, TravelStatus.OK),
                        tuple(second, 60, first, 20, 15, -15, TravelStatus.CONFLICT),
                        tuple(third, 60, second, null, 15, null, TravelStatus.UNKNOWN));
        assertThat(plans.get(0).needsCheck()).isTrue();
        assertThat(plans.get(1).visits()).isEmpty();
        assertThat(map.asked).containsExactly(List.of(ORIGIN, CUSTOMER), List.of(CUSTOMER, OTHER));
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
        jdbc.update("UPDATE inquiry SET address = ?, lat = ?, lng = ?, work_type_id = ?, status = 'SCHEDULING' "
                + "WHERE id = ?", ADDRESS, CUSTOMER.lat(), CUSTOMER.lng(), workTypeId, session.inquiryId());
        jdbc.update("INSERT INTO inquiry_field (inquiry_id, field_key, field_value, source, status) "
                + "VALUES (?, 'symptom', '온수가 안 나와요', 'CUSTOMER', 'CONFIRMED')", session.inquiryId());
        return session;
    }

    private long reservation(LocalDate date, int startHour, int startMinute, int endHour, int endMinute,
                             GeoPoint location, String status) {
        return jdbc.queryForObject("INSERT INTO reservation (owner_id, start_at, end_at, address, lat, lng, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id", Long.class, ownerId,
                at(date, startHour, startMinute), at(date, endHour, endMinute), ADDRESS,
                location == null ? null : location.lat(), location == null ? null : location.lng(), status);
    }

    private static OffsetDateTime at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atOffset(ZoneOffset.ofHours(9));
    }

    private static List<String> times(LocalDate date, String... hhmm) {
        return java.util.Arrays.stream(hhmm).map(value -> date + "T" + value + ":00+09:00").toList();
    }

    private static GeoPoint point(String lat, String lng) {
        return new GeoPoint(new BigDecimal(lat), new BigDecimal(lng));
    }

    private String inquiryStatus(Session session) {
        return jdbc.queryForObject("SELECT status FROM inquiry WHERE id = ?", String.class, session.inquiryId());
    }

    private int notifications(Session session) {
        return jdbc.queryForObject("SELECT count(*) FROM notification WHERE ref_type = 'INQUIRY' AND ref_id = ? "
                + "AND event_type = 'NEEDS_CHECK'", Integer.class, session.inquiryId());
    }

    private ResultActions getAs(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }
}
