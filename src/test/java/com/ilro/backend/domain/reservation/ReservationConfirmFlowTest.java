package com.ilro.backend.domain.reservation;

import com.ilro.backend.domain.inquiry.service.IntakeTerms;
import com.ilro.backend.domain.reservation.service.MapLookup;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 고객 예약 확정·본인 조회 (C-13·C-14, 명세 1.6·1.7·4.3·5.2, BE-11)
// 로컬 프로필이라 가짜 지도(FakeMapClient): 같은 좌표는 이동 0분, 서비스 지역을 정했으면 지역 안
@SpringBootTest
class ReservationConfirmFlowTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String ADDRESS = "경기 고양시 덕양구 화정로 1";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    // 동의 약관 버전은 설정값(INTAKE_TERMS_VERSION)을 그대로 씀 - 개발자 .env에서 버전을 바꿔도 접수가 막히지 않게
    @Autowired
    private IntakeTerms intakeTerms;

    // 잠그기 전 검사 도중에 다른 요청을 끼워 넣을 때만 씀 - 평소에는 실제 동작 그대로
    @MockitoSpyBean
    private MapLookup mapLookup;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private long ownerId;
    private long workTypeId;
    // 다음다음 주 월요일 - 지난 시각이 끼지 않게
    private LocalDate monday;

    private record Session(long inquiryId, String token, String phone) {

        String reservations() {
            return "/api/v1/customer/inquiries/" + inquiryId + "/reservations";
        }
    }

    // 평일 09:00~18:00, 토요일 휴무, 점심 12~13시, 하루 2건, 여유시간 15분, 대표 연락처 없음
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
    @DisplayName("확정 - 201 CONFIRMED, 당시 작업·여유·이동시간 저장, 고객 문자·사장님 푸시 PENDING, 문의 BOOKED")
    void confirm() throws Exception {
        Session session = verifiedInquiry();
        int versionBefore = inquiryVersion(session);
        String key = UUID.randomUUID().toString();

        String body = confirm(session, key, time(monday, "10:00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.result.startAt").value(time(monday, "10:00")))
                .andExpect(jsonPath("$.result.endAt").value(time(monday, "11:00")))
                .andExpect(jsonPath("$.result.version").value(0))
                .andExpect(jsonPath("$.result.notifications", hasSize(2)))
                .andExpect(jsonPath("$.result.notifications[*].recipient").value(contains("CUSTOMER", "OWNER")))
                .andExpect(jsonPath("$.result.notifications[*].channel").value(contains("SMS", "WEB_PUSH")))
                .andExpect(jsonPath("$.result.notifications[*].sendStatus").value(contains("PENDING", "PENDING")))
                .andReturn().getResponse().getContentAsString();
        long reservationId = ((Number) JsonPath.read(body, "$.result.reservationId")).longValue();

        assertThat(jdbc.queryForMap("SELECT inquiry_id, customer_id IS NOT NULL AS has_customer, status, "
                        + "work_minutes, buffer_minutes, travel_time_minutes, is_manual, idempotency_key, address, "
                        + "work_type_id FROM reservation WHERE id = ?", reservationId))
                .containsEntry("inquiry_id", session.inquiryId())
                .containsEntry("has_customer", true)
                .containsEntry("status", "CONFIRMED")
                .containsEntry("work_minutes", 60)
                .containsEntry("buffer_minutes", 15)
                .containsEntry("travel_time_minutes", 0)
                .containsEntry("is_manual", false)
                .containsEntry("idempotency_key", key)
                .containsEntry("address", ADDRESS)
                .containsEntry("work_type_id", workTypeId);
        assertThat(jdbc.queryForList("SELECT dedupe_key FROM notification WHERE ref_type = 'RESERVATION' "
                + "AND ref_id = ? ORDER BY id", String.class, reservationId)).containsExactly(
                "RESERVATION:" + reservationId + ":CONFIRMED:CUSTOMER:SMS",
                "RESERVATION:" + reservationId + ":CONFIRMED:OWNER:WEB_PUSH",
                "RESERVATION:" + reservationId + ":CONFIRMED:OWNER:IN_APP");
        assertThat(inquiryStatus(session)).isEqualTo("BOOKED");
        assertThat(inquiryVersion(session)).isEqualTo(versionBefore + 1);
    }

    @Test
    @DisplayName("같은 키 다시 - 대소문자만 달라도 처음 결과 200, 다른 시각이면 409 IDEMPOTENCY_KEY_REUSED, 새 키면 기존 예약 409")
    void idempotency() throws Exception {
        Session session = verifiedInquiry();
        String key = UUID.randomUUID().toString();
        long reservationId = reservationId(confirm(session, key, time(monday, "10:00"))
                .andExpect(status().isCreated()));

        // 같은 시각을 UTC로 적어도 같은 요청 (지문은 UTC로 바꿔 계산)
        confirm(session, key.toUpperCase(Locale.ROOT), monday + "T01:00:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.reservationId").value(reservationId))
                .andExpect(jsonPath("$.result.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.result.notifications", hasSize(2)));

        confirm(session, key, time(monday, "14:00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        confirm(session, UUID.randomUUID().toString(), time(monday, "14:00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.result.reservationId").value(reservationId));

        // 다른 문의가 같은 키를 쓰면 지문(요청 주체)이 달라 남의 예약 결과를 받지 못함
        Session other = verifiedInquiry();
        confirm(other, key, time(monday, "10:00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(reservations()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE ref_type = 'RESERVATION' "
                + "AND ref_id = ?", Integer.class, reservationId)).isEqualTo(3);
    }

    @Test
    @DisplayName("요청 형식 - 키 없음·UUID 아님·시각 없음·초 단위 시각은 400, 저장 안 함")
    void invalidRequest() throws Exception {
        Session session = verifiedInquiry();
        mockMvc.perform(post(session.reservations()).header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON).content(startAt(time(monday, "10:00"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result[0].field").value("Idempotency-Key"));
        confirm(session, "not-a-uuid", time(monday, "10:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("Idempotency-Key"));
        mockMvc.perform(withKey(post(session.reservations()), session, UUID.randomUUID().toString())
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        confirm(session, UUID.randomUUID().toString(), monday + "T10:00:30+09:00")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("startAt"));
        assertThat(reservations()).isZero();
        assertThat(inquiryStatus(session)).isEqualTo("SCHEDULING");
    }

    @Test
    @DisplayName("고를 수 없는 시각 - 후보 간격(30분)에 없는 시각·1년 넘게 떨어진 날짜는 400, 저장·사장님 확인 없음")
    void notOfferedStart() throws Exception {
        Session session = verifiedInquiry();
        // 09:00부터 30분 간격 - 겹치는 예약이 없어도 그 사이 시각은 받지 않음
        for (String startAt : List.of(time(monday, "10:07"), time(monday, "10:15"), time(monday.plusYears(2), "10:00"))) {
            confirm(session, UUID.randomUUID().toString(), startAt)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.result[0].field").value("startAt"));
        }
        // 달력 끝의 날짜 - 날짜 계산이 넘쳐 500이 나지 않음
        confirm(session, UUID.randomUUID().toString(), "+999999999-12-31T01:00:00Z")
                .andExpect(status().isBadRequest());
        assertThat(reservations()).isZero();
        assertThat(inquiryStatus(session)).isEqualTo("SCHEDULING");
        assertThat(needsCheck(session)).isZero();

        // 간격 위의 시각은 그대로 확정
        confirm(session, UUID.randomUUID().toString(), time(monday, "10:30"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("예약 상세 - 답할 기한이 지난 제안은 상태가 PROPOSED로 남아 있어도 진행 중 변경으로 보여 주지 않음")
    void expiredProposalHidden() throws Exception {
        Session session = verifiedInquiry();
        long reservationId = reservationId(confirm(session, UUID.randomUUID().toString(), time(monday, "10:00"))
                .andExpect(status().isCreated()));
        long changeId = jdbc.queryForObject("INSERT INTO reservation_change (reservation_id, change_type, "
                + "requested_by, prev_start_at, prev_end_at, proposed_start_at, proposed_end_at, expires_at, status, "
                + "base_version) VALUES (?, 'CHANGE', 'OWNER', ?, ?, ?, ?, CURRENT_TIMESTAMP + interval '1 hour', "
                + "'PROPOSED', 0) RETURNING id", Long.class, reservationId, at(monday, 10, 0), at(monday, 11, 0),
                at(monday, 14, 0), at(monday, 15, 0));

        getAs(session.token(), "/api/v1/customer/reservations/" + reservationId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.activeChange.status").value("PROPOSED"));

        jdbc.update("UPDATE reservation_change SET expires_at = CURRENT_TIMESTAMP - interval '1 second' WHERE id = ?",
                changeId);
        getAs(session.token(), "/api/v1/customer/reservations/" + reservationId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.activeChange").doesNotExist());
        // 조회는 저장된 상태를 바꾸지 않음
        assertThat(jdbc.queryForObject("SELECT status FROM reservation_change WHERE id = ?", String.class, changeId))
                .isEqualTo("PROPOSED");
    }

    @Test
    @DisplayName("번호 인증 - 방문정보 번호 인증 전·번호를 바꾼 뒤에는 403 PHONE_NOT_VERIFIED, 문의는 그대로")
    void phoneNotVerified() throws Exception {
        Session noCustomer = readyInquiry();
        confirm(noCustomer, UUID.randomUUID().toString(), time(monday, "10:00"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PHONE_NOT_VERIFIED"));

        Session reset = verifiedInquiry();
        jdbc.update("UPDATE inquiry SET phone_verified_at = NULL WHERE id = ?", reset.inquiryId());
        int versionBefore = inquiryVersion(reset);
        confirm(reset, UUID.randomUUID().toString(), time(monday, "10:00"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PHONE_NOT_VERIFIED"));

        assertThat(reservations()).isZero();
        assertThat(inquiryStatus(reset)).isEqualTo("SCHEDULING");
        assertThat(inquiryVersion(reset)).isEqualTo(versionBefore);
        assertThat(needsCheck(reset)).isZero();
    }

    @Test
    @DisplayName("시간 - 겹침·점심·하루 한도·휴무·지난 시각·앞뒤 여유시간이면 409 SLOT_UNAVAILABLE + 이유, 문의는 그대로")
    void slotUnavailable() throws Exception {
        LocalDate tuesday = monday.plusDays(1);
        reservation(monday, 10, "CONFIRMED");
        reservation(tuesday, 9, "CONFIRMED");
        reservation(tuesday, 14, "COMPLETED");

        assertSlotUnavailable(time(monday, "10:00"), "OVERLAP");
        assertSlotUnavailable(time(monday, "11:00"), "TRAVEL_CONFLICT");
        assertSlotUnavailable(time(monday, "12:00"), "LUNCH_BREAK");
        assertSlotUnavailable(time(tuesday, "16:00"), "DAILY_LIMIT_REACHED");
        assertSlotUnavailable(time(monday.minusDays(2), "10:00"), "OUTSIDE_BUSINESS_HOURS");
        assertSlotUnavailable(time(LocalDate.now(KST).minusDays(1), "10:00"), "PAST");
        jdbc.update("INSERT INTO owner_block (owner_id, start_at, end_at, reason) VALUES (?, ?, ?, '개인 일정')",
                ownerId, at(monday, 15, 0), at(monday, 16, 0));
        assertSlotUnavailable(time(monday, "15:00"), "BLOCKED");
    }

    @Test
    @DisplayName("자동 예약 조건 - 업체·문의 조건이 깨지거나 이동시간을 셀 수 없으면 422, 사장님 확인 + 알림 1, 저장 안 함")
    void askOwner() throws Exception {
        assertAskedOwner("AUTO_BOOKING_DISABLED", time(monday, "10:00"),
                id -> jdbc.update("UPDATE owner SET auto_booking_enabled = false WHERE id = ?", ownerId));
        jdbc.update("UPDATE owner SET auto_booking_enabled = true WHERE id = ?", ownerId);

        assertAskedOwner("REQUIRED_INFO_UNCONFIRMED", time(monday, "10:00"),
                id -> jdbc.update("DELETE FROM inquiry_field WHERE inquiry_id = ?", id));
        assertAskedOwner("OUT_OF_SERVICE_AREA", time(monday, "10:00"),
                id -> jdbc.update("UPDATE inquiry SET lat = NULL, lng = NULL WHERE id = ?", id));

        // 앞 방문지 좌표가 없어 이동시간을 셀 수 없음 - 시간을 임의로 만들지 않음
        assertAskedOwner("TRAVEL_TIME_UNAVAILABLE", time(monday, "11:00"),
                id -> jdbc.update("INSERT INTO reservation (owner_id, start_at, end_at, address, status) "
                        + "VALUES (?, ?, ?, '주소만 있음', 'CONFIRMED')", ownerId, at(monday, 9, 0),
                        at(monday, 10, 0)));
    }

    @Test
    @DisplayName("예약할 수 없는 상담 - 사장님 응대 중 409(상태 그대로)·종료 409·남의 문의 404·사장님 토큰 403")
    void notConfirmable() throws Exception {
        Session owner = verifiedInquiry();
        jdbc.update("UPDATE inquiry SET handler_mode = 'OWNER' WHERE id = ?", owner.inquiryId());
        confirm(owner, UUID.randomUUID().toString(), time(monday, "10:00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNER_HANDLING"));
        assertThat(inquiryStatus(owner)).isEqualTo("SCHEDULING");
        assertThat(needsCheck(owner)).isZero();

        Session closed = verifiedInquiry();
        jdbc.update("UPDATE inquiry SET status = 'CLOSED' WHERE id = ?", closed.inquiryId());
        confirm(closed, UUID.randomUUID().toString(), time(monday, "10:00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INQUIRY_CLOSED"));

        Session session = verifiedInquiry();
        mockMvc.perform(withKey(post(closed.reservations()), session, UUID.randomUUID().toString())
                        .content(startAt(time(monday, "10:00"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(session.reservations())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accounts.ownerToken(ownerId))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(startAt(time(monday, "10:00"))))
                .andExpect(status().isForbidden());
        assertThat(reservations()).isZero();
    }

    @Test
    @DisplayName("동시 확정 - 두 문의가 같은 시각을 동시에 고르면 하나만 201, 나머지는 409 SLOT_UNAVAILABLE(OVERLAP)")
    void concurrentSameSlot() throws Exception {
        Session first = verifiedInquiry();
        Session second = verifiedInquiry();
        List<MockHttpServletResponse> responses = runTogether(
                () -> confirm(first, UUID.randomUUID().toString(), time(monday, "10:00")).andReturn().getResponse(),
                () -> confirm(second, UUID.randomUUID().toString(), time(monday, "10:00")).andReturn().getResponse());

        assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(201, 409);
        MockHttpServletResponse loser = responses.stream().filter(r -> r.getStatus() == 409).findFirst().orElseThrow();
        String body = loser.getContentAsString();
        assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("SLOT_UNAVAILABLE");
        assertThat((String) JsonPath.read(body, "$.result.reason")).isEqualTo("OVERLAP");
        assertThat(reservations()).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 확정 - 같은 키로 두 번 동시에 보내면 하나는 201, 하나는 같은 예약을 200, 예약은 1건")
    void concurrentSameKey() throws Exception {
        Session session = verifiedInquiry();
        String key = UUID.randomUUID().toString();
        List<MockHttpServletResponse> responses = runTogether(
                () -> confirm(session, key, time(monday, "10:00")).andReturn().getResponse(),
                () -> confirm(session, key, time(monday, "10:00")).andReturn().getResponse());

        assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(201, 200);
        List<Object> ids = new ArrayList<>();
        for (MockHttpServletResponse response : responses) {
            ids.add(((Number) JsonPath.read(response.getContentAsString(), "$.result.reservationId")).longValue());
        }
        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(reservations()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 키 경합 - 처음 확인과 잠그기 전 검사 사이에 같은 요청이 먼저 저장을 끝내도 409가 아니라 같은 예약을 200")
    void sameKeySavedDuringPrecheck() throws Exception {
        Session session = verifiedInquiry();
        String key = UUID.randomUUID().toString();
        // 뒤 요청이 잠그기 전 검사(서비스 지역 확인)를 하는 도중에, 같은 키의 앞 요청이 다른 스레드에서 끝까지 저장
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<MockHttpServletResponse> first = new AtomicReference<>();
        doAnswer(invocation -> {
            if (interrupted.compareAndSet(false, true)) {
                ExecutorService other = Executors.newSingleThreadExecutor();
                try {
                    first.set(other.submit(() -> confirm(session, key, time(monday, "10:00")).andReturn()
                            .getResponse()).get(30, TimeUnit.SECONDS));
                } finally {
                    other.shutdownNow();
                }
            }
            return invocation.callRealMethod();
        }).when(mapLookup).serviceArea(any(), any());

        MockHttpServletResponse second = confirm(session, key, time(monday, "10:00")).andReturn().getResponse();

        assertThat(first.get().getStatus()).isEqualTo(201);
        assertThat(second.getStatus()).as(second.getContentAsString()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(second.getContentAsString(), "$.result.reservationId")).longValue())
                .isEqualTo(((Number) JsonPath.read(first.get().getContentAsString(), "$.result.reservationId"))
                        .longValue());
        assertThat(reservations()).isEqualTo(1);
        assertThat(needsCheck(session)).isZero();
        assertThat(inquiryStatus(session)).isEqualTo("BOOKED");
    }

    @Test
    @DisplayName("내 예약 목록 - 예약 조회 고객 토큰만, 그 번호·그 업체 예약을 방문일 늦은 순으로 (취소 포함)")
    void customerList() throws Exception {
        Session session = verifiedInquiry();
        long confirmed = reservationId(confirm(session, UUID.randomUUID().toString(), time(monday, "10:00"))
                .andExpect(status().isCreated()));
        long customerId = jdbc.queryForObject("SELECT customer_id FROM inquiry WHERE id = ?", Long.class,
                session.inquiryId());
        long canceled = jdbc.queryForObject("INSERT INTO reservation (owner_id, customer_id, start_at, end_at, "
                + "address, status) VALUES (?, ?, ?, ?, ?, 'CANCELED') RETURNING id", Long.class, ownerId,
                customerId, at(monday.plusDays(1), 14, 0), at(monday.plusDays(1), 15, 0), ADDRESS);
        // 다른 번호의 예약 - 보이면 안 됨
        reservationOf(verifiedInquiry(), monday.plusDays(2), 10);

        String lookup = accounts.token("CUSTOMER", ownerId, null, session.phone(), "RESERVATION_LOOKUP",
                "CURRENT_TIMESTAMP + interval '1 day'");
        getAs(lookup, "/api/v1/customer/reservations")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.reservations[*].reservationId").value(contains(
                        (int) canceled, (int) confirmed)))
                .andExpect(jsonPath("$.result.reservations[0].status").value("CANCELED"))
                .andExpect(jsonPath("$.result.reservations[1].workCategory").value("REPAIR"))
                .andExpect(jsonPath("$.result.reservations[1].startAt").value(time(monday, "10:00")))
                .andExpect(jsonPath("$.result.reservations[1].address").value(ADDRESS));

        // 다른 업체의 같은 번호 토큰은 그 업체 예약만 (없음)
        long otherOwner = accounts.approvedOwner(accounts.newPhone());
        String otherLookup = accounts.token("CUSTOMER", otherOwner, null, session.phone(), "RESERVATION_LOOKUP",
                "CURRENT_TIMESTAMP + interval '1 day'");
        getAs(otherLookup, "/api/v1/customer/reservations")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.reservations", hasSize(0)));

        getAs(session.token(), "/api/v1/customer/reservations")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        String recovery = accounts.token("CUSTOMER", ownerId, null, session.phone(), "INQUIRY_ACCESS",
                "CURRENT_TIMESTAMP + interval '1 day'");
        getAs(recovery, "/api/v1/customer/reservations")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("VERIFICATION_PURPOSE_MISMATCH"));
    }

    @Test
    @DisplayName("예약 상세 - 문의 토큰은 그 문의 예약만, 고객 토큰은 그 번호 예약만, 진행 중 변경 포함, 남의 예약은 404")
    void customerDetail() throws Exception {
        Session session = verifiedInquiry();
        long reservationId = reservationId(confirm(session, UUID.randomUUID().toString(), time(monday, "10:00"))
                .andExpect(status().isCreated()));
        String ownerPhone = jdbc.queryForObject("SELECT phone FROM owner WHERE id = ?", String.class, ownerId);

        getAs(session.token(), "/api/v1/customer/reservations/" + reservationId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.reservationId").value(reservationId))
                .andExpect(jsonPath("$.result.inquiryId").value(session.inquiryId()))
                .andExpect(jsonPath("$.result.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.result.startAt").value(time(monday, "10:00")))
                .andExpect(jsonPath("$.result.endAt").value(time(monday, "11:00")))
                .andExpect(jsonPath("$.result.address").value(ADDRESS))
                .andExpect(jsonPath("$.result.workCategory").value("REPAIR"))
                .andExpect(jsonPath("$.result.workMinutes").value(60))
                .andExpect(jsonPath("$.result.businessName").value("테스트보일러"))
                .andExpect(jsonPath("$.result.contactPhone").value(ownerPhone))
                .andExpect(jsonPath("$.result.version").value(0))
                .andExpect(jsonPath("$.result.activeChange").doesNotExist());

        jdbc.update("INSERT INTO reservation_change (reservation_id, change_type, requested_by, request_note, "
                + "prev_start_at, prev_end_at, status, base_version) VALUES (?, 'CHANGE', 'CUSTOMER', '오후로', "
                + "?, ?, 'REQUESTED', 0)", reservationId, at(monday, 10, 0), at(monday, 11, 0));
        jdbc.update("UPDATE owner SET contact_phone = '0311234567' WHERE id = ?", ownerId);
        String lookup = accounts.token("CUSTOMER", ownerId, null, session.phone(), "RESERVATION_LOOKUP",
                "CURRENT_TIMESTAMP + interval '1 day'");
        getAs(lookup, "/api/v1/customer/reservations/" + reservationId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.contactPhone").value("0311234567"))
                .andExpect(jsonPath("$.result.activeChange.status").value("REQUESTED"))
                .andExpect(jsonPath("$.result.activeChange.requestedBy").value("CUSTOMER"))
                .andExpect(jsonPath("$.result.activeChange.previous.startAt").value(time(monday, "10:00")))
                .andExpect(jsonPath("$.result.activeChange.proposed.startAt").doesNotExist());

        Session other = verifiedInquiry();
        getAs(other.token(), "/api/v1/customer/reservations/" + reservationId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        String otherPhone = accounts.token("CUSTOMER", ownerId, null, other.phone(), "RESERVATION_LOOKUP",
                "CURRENT_TIMESTAMP + interval '1 day'");
        getAs(otherPhone, "/api/v1/customer/reservations/" + reservationId)
                .andExpect(status().isNotFound());
        getAs(session.token(), "/api/v1/customer/reservations/999999999")
                .andExpect(status().isNotFound());
    }

    // ---------- 도움 함수 ----------

    // 예약 준비가 끝난 상담 - 시간 선택 단계, 주소·좌표, 수리 작업, 증상 확인됨 (방문정보 번호는 아직 없음)
    private Session readyInquiry() throws Exception {
        String body = mockMvc.perform(post("/api/v1/public/intake/" + accounts.linkToken(ownerId) + "/inquiries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consents\":[{\"type\":\"PRIVACY_REQUIRED\",\"termsVersion\":\""
                                + intakeTerms.version() + "\"},{\"type\":\"PHOTO\",\"termsVersion\":\""
                                + intakeTerms.version() + "\"}]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Session session = new Session(((Number) JsonPath.read(body, "$.result.inquiryId")).longValue(),
                JsonPath.read(body, "$.result.inquiryToken"), null);
        jdbc.update("UPDATE inquiry SET address = ?, lat = 37.634, lng = 126.832, work_type_id = ?, "
                + "status = 'SCHEDULING' WHERE id = ?", ADDRESS, workTypeId, session.inquiryId());
        jdbc.update("INSERT INTO inquiry_field (inquiry_id, field_key, field_value, source, status) "
                + "VALUES (?, 'symptom', '온수가 안 나와요', 'CUSTOMER', 'CONFIRMED')", session.inquiryId());
        return session;
    }

    // 방문정보 번호까지 문자 인증(CUSTOMER_VERIFY, BE-18)을 마친 상담
    private Session verifiedInquiry() throws Exception {
        Session ready = readyInquiry();
        String phone = accounts.newPhone();
        long customerId = jdbc.queryForObject("INSERT INTO customer (owner_id, name, phone) VALUES (?, '김고객', ?) "
                + "RETURNING id", Long.class, ownerId, phone);
        jdbc.update("UPDATE inquiry SET customer_id = ?, phone_verified_at = CURRENT_TIMESTAMP WHERE id = ?",
                customerId, ready.inquiryId());
        return new Session(ready.inquiryId(), ready.token(), phone);
    }

    private void assertSlotUnavailable(String startAt, String reason) throws Exception {
        Session session = verifiedInquiry();
        confirm(session, UUID.randomUUID().toString(), startAt)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_UNAVAILABLE"))
                .andExpect(jsonPath("$.result.reason").value(reason));
        assertThat(inquiryStatus(session)).as(reason).isEqualTo("SCHEDULING");
        assertThat(needsCheck(session)).as(reason).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation WHERE inquiry_id = ?", Integer.class,
                session.inquiryId())).as(reason).isZero();
    }

    // 새 상담에 조건 하나를 깨뜨리고 확정 → 422 그 코드, 문의는 사장님 확인, 알림 1, version 오름, 예약 없음
    private void assertAskedOwner(String code, String startAt, LongConsumer breakIt) throws Exception {
        Session session = verifiedInquiry();
        breakIt.accept(session.inquiryId());
        int versionBefore = inquiryVersion(session);
        confirm(session, UUID.randomUUID().toString(), startAt)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value(code));
        assertThat(inquiryStatus(session)).as(code).isEqualTo("OWNER_CHECK");
        assertThat(needsCheck(session)).as(code).isEqualTo(1);
        assertThat(inquiryVersion(session)).as(code).isEqualTo(versionBefore + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation WHERE inquiry_id = ?", Integer.class,
                session.inquiryId())).as(code).isZero();
        jdbc.update("DELETE FROM reservation WHERE owner_id = ? AND inquiry_id IS NULL", ownerId);
    }

    private ResultActions confirm(Session session, String key, String startAt) throws Exception {
        return mockMvc.perform(withKey(post(session.reservations()), session, key).content(startAt(startAt)));
    }

    private static MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder request, Session session,
                                                         String key) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private static String startAt(String startAt) {
        return "{\"startAt\":\"" + startAt + "\"}";
    }

    private static long reservationId(ResultActions actions) throws Exception {
        return ((Number) JsonPath.read(actions.andReturn().getResponse().getContentAsString(),
                "$.result.reservationId")).longValue();
    }

    // 다른 문의의 확정 예약 (그 문의의 고객으로)
    private void reservationOf(Session session, LocalDate date, int hour) {
        jdbc.update("INSERT INTO reservation (inquiry_id, owner_id, customer_id, start_at, end_at, address, lat, lng, "
                        + "status) SELECT id, owner_id, customer_id, ?, ?, ?, 37.634, 126.832, 'CONFIRMED' FROM inquiry "
                        + "WHERE id = ?", at(date, hour, 0), at(date, hour + 1, 0), ADDRESS, session.inquiryId());
    }

    private void reservation(LocalDate date, int hour, String status) {
        jdbc.update("INSERT INTO reservation (owner_id, start_at, end_at, address, lat, lng, status) "
                + "VALUES (?, ?, ?, ?, 37.634, 126.832, ?)", ownerId, at(date, hour, 0), at(date, hour + 1, 0),
                ADDRESS, status);
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

    private int reservations() {
        return jdbc.queryForObject("SELECT count(*) FROM reservation WHERE owner_id = ? AND inquiry_id IS NOT NULL",
                Integer.class, ownerId);
    }

    private String inquiryStatus(Session session) {
        return jdbc.queryForObject("SELECT status FROM inquiry WHERE id = ?", String.class, session.inquiryId());
    }

    private int inquiryVersion(Session session) {
        return jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?", Integer.class, session.inquiryId());
    }

    private int needsCheck(Session session) {
        return jdbc.queryForObject("SELECT count(*) FROM notification WHERE ref_type = 'INQUIRY' AND ref_id = ? "
                + "AND event_type = 'NEEDS_CHECK' AND channel = 'IN_APP'", Integer.class, session.inquiryId());
    }

    private ResultActions getAs(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }
}
