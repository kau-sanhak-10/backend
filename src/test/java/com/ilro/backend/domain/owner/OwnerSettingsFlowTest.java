package com.ilro.backend.domain.owner;

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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 업체 정보·운영 규칙 API (BE-03, 명세 4.4) - 실제 토큰으로 호출, 업체는 토큰으로만 정해짐
@SpringBootTest
class OwnerSettingsFlowTest {

    private static final String BUSINESS = "/api/v1/owner/business";
    private static final String WORK_TYPES = "/api/v1/owner/work-types";
    private static final String SERVICE_AREAS = "/api/v1/owner/service-areas";
    private static final String BOOKING = "/api/v1/owner/booking-settings";
    private static final String AUTO_BOOKING = "/api/v1/owner/booking-settings/auto-booking";

    // 월~금 09:00~18:00, 토 09:00~13:00, 일 휴무
    private static final String WEEK_HOURS = """
            [ {"dayOfWeek":1,"openTime":"09:00","closeTime":"18:00","isClosed":false},
              {"dayOfWeek":2,"openTime":"09:00","closeTime":"18:00","isClosed":false},
              {"dayOfWeek":3,"openTime":"09:00","closeTime":"18:00","isClosed":false},
              {"dayOfWeek":4,"openTime":"09:00","closeTime":"18:00","isClosed":false},
              {"dayOfWeek":5,"openTime":"09:00","closeTime":"18:00","isClosed":false},
              {"dayOfWeek":6,"openTime":"09:00","closeTime":"13:00","isClosed":false},
              {"dayOfWeek":7,"openTime":null,"closeTime":null,"isClosed":true} ]""";
    private static final String ALL_CLOSED = """
            [ {"dayOfWeek":1,"isClosed":true}, {"dayOfWeek":2,"isClosed":true}, {"dayOfWeek":3,"isClosed":true},
              {"dayOfWeek":4,"isClosed":true}, {"dayOfWeek":5,"isClosed":true}, {"dayOfWeek":6,"isClosed":true},
              {"dayOfWeek":7,"isClosed":true} ]""";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private long ownerId;
    private String token;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        accounts = new TestAccounts(jdbc);
        ownerId = accounts.approvedOwner(accounts.newPhone());
        token = accounts.ownerToken(ownerId);
    }

    @AfterEach
    void tearDown() {
        accounts.cleanUp();
    }

    @Test
    @DisplayName("업체 정보 저장·조회 - 대표 연락처는 숫자만으로 저장")
    void businessInfoRoundTrip() throws Exception {
        putAs(token, BUSINESS, """
                {"businessName":" 새보일러 ","contactPhone":"031-123-4567","address":"경기 고양시 덕양구 화정로 1",
                 "lat":37.6340000,"lng":126.8320000,"customerNotice":"방문 전 연락드려요","serviceDescription":"점검·수리"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.businessName").value("새보일러"))
                .andExpect(jsonPath("$.result.contactPhone").value("0311234567"));

        getAs(token, BUSINESS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.address").value("경기 고양시 덕양구 화정로 1"))
                .andExpect(jsonPath("$.result.lat").value(37.634))
                .andExpect(jsonPath("$.result.customerNotice").value("방문 전 연락드려요"));

        // 비울 칸은 null - 주소를 비우면 좌표도 같이 비움
        putAs(token, BUSINESS, """
                {"businessName":"새보일러","contactPhone":null,"address":null,"lat":null,"lng":null,
                 "customerNotice":null,"serviceDescription":null}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.contactPhone").doesNotExist())
                .andExpect(jsonPath("$.result.address").doesNotExist());
    }

    @Test
    @DisplayName("업체 정보 - 빈 문자열·좌표 없는 주소·전화번호가 아닌 연락처는 400")
    void businessInfoValidation() throws Exception {
        putAs(token, BUSINESS, """
                {"businessName":"새보일러","customerNotice":"  "}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("customerNotice"));
        putAs(token, BUSINESS, """
                {"businessName":"새보일러","address":"경기 고양시","lat":null,"lng":null,"contactPhone":"12-34"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[*].field", containsInAnyOrder("contactPhone", "address")));
        putAs(token, BUSINESS, """
                {"businessName":null}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("businessName"));
    }

    @Test
    @DisplayName("내 이름 수정 - 빈 이름은 400")
    void updateOwnerName() throws Exception {
        patchAs(token, "/api/v1/owner/me", """
                {"ownerName":"정사장"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.ownerName").value("정사장"));
        patchAs(token, "/api/v1/owner/me", """
                {"ownerName":" "}""")
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT owner_name FROM owner WHERE id = ?", String.class, ownerId))
                .isEqualTo("정사장");
    }

    @Test
    @DisplayName("작업 종류 - 처음엔 4가지 모두 제공 안 함·60분, 보내지 않은 값은 유지, 빠진 작업은 지우지 않고 제공 안 함")
    void workTypes() throws Exception {
        getAs(token, WORK_TYPES)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.workTypes", hasSize(4)))
                .andExpect(jsonPath("$.result.workTypes[0].workCategory").value("INSPECTION"))
                .andExpect(jsonPath("$.result.workTypes[0].isActive").value(false))
                .andExpect(jsonPath("$.result.workTypes[0].durationMinutes").value(60));

        putAs(token, WORK_TYPES, """
                {"workTypes":[{"workCategory":"REPAIR","isActive":true,"durationMinutes":90,"autoBookingAllowed":true},
                              {"workCategory":"INSPECTION","isActive":true}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.workTypes[0].isActive").value(true))
                .andExpect(jsonPath("$.result.workTypes[0].durationMinutes").value(60))
                .andExpect(jsonPath("$.result.workTypes[0].autoBookingAllowed").value(false))
                .andExpect(jsonPath("$.result.workTypes[1].durationMinutes").value(90))
                .andExpect(jsonPath("$.result.workTypes[1].autoBookingAllowed").value(true))
                .andExpect(jsonPath("$.result.workTypes[2].isActive").value(false));

        putAs(token, WORK_TYPES, """
                {"workTypes":[{"workCategory":"REPAIR","isActive":true}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.workTypes[0].isActive").value(false))
                .andExpect(jsonPath("$.result.workTypes[1].durationMinutes").value(90))
                .andExpect(jsonPath("$.result.workTypes[1].autoBookingAllowed").value(true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_work_type WHERE owner_id = ?", Integer.class, ownerId))
                .isEqualTo(2);

        // 제공을 끄면 자동 예약 허용도 같이 꺼짐
        putAs(token, WORK_TYPES, """
                {"workTypes":[{"workCategory":"REPAIR","isActive":false}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.workTypes[1].autoBookingAllowed").value(false));
    }

    @Test
    @DisplayName("작업 종류 - 같은 종류 두 번은 409, 제공 안 하는 작업의 자동 예약 허용·0분은 400")
    void workTypeValidation() throws Exception {
        putAs(token, WORK_TYPES, """
                {"workTypes":[{"workCategory":"REPAIR","isActive":true},{"workCategory":"REPAIR","isActive":false}]}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_TYPE_DUPLICATED"));
        putAs(token, WORK_TYPES, """
                {"workTypes":[{"workCategory":"LEAK","isActive":false,"autoBookingAllowed":true}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("workTypes[0].autoBookingAllowed"));
        putAs(token, WORK_TYPES, """
                {"workTypes":[{"workCategory":"LEAK","isActive":true,"durationMinutes":0}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("workTypes[0].durationMinutes"));
    }

    @Test
    @DisplayName("서비스 지역 - 전체 교체 (남는 지역은 이름 갱신, 빠진 지역 삭제, 새 지역 추가), 중복은 409")
    void serviceAreas() throws Exception {
        putAs(token, SERVICE_AREAS, """
                {"serviceAreas":[{"regionCode":"4128100000","regionName":"고양시 덕양구"},
                                 {"regionCode":"4128500000","regionName":"고양시 일산동구"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.serviceAreas", hasSize(2)));

        putAs(token, SERVICE_AREAS, """
                {"serviceAreas":[{"regionCode":"4128500000","regionName":"일산동구"},
                                 {"regionCode":"4128700000","regionName":"고양시 일산서구"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.serviceAreas", hasSize(2)))
                .andExpect(jsonPath("$.result.serviceAreas[0].regionCode").value("4128500000"))
                .andExpect(jsonPath("$.result.serviceAreas[0].regionName").value("일산동구"))
                .andExpect(jsonPath("$.result.serviceAreas[1].regionCode").value("4128700000"));

        putAs(token, SERVICE_AREAS, """
                {"serviceAreas":[{"regionCode":"4128500000","regionName":"a"},{"regionCode":"4128500000","regionName":"b"}]}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_AREA_DUPLICATED"));
        putAs(token, SERVICE_AREAS, """
                {"serviceAreas":[{"regionCode":"고양","regionName":"고양시"}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("serviceAreas[0].regionCode"));

        getAs(token, SERVICE_AREAS)
                .andExpect(jsonPath("$.result.serviceAreas[*].regionCode", containsInAnyOrder("4128500000", "4128700000")));
    }

    @Test
    @DisplayName("예약 기준 - 처음엔 7일 모두 쉬는 날·여유 15분·CAR, 저장하면 요일별 1행씩 갱신")
    void bookingSettings() throws Exception {
        getAs(token, BOOKING)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.businessHours", hasSize(7)))
                .andExpect(jsonPath("$.result.businessHours[0].isClosed").value(true))
                .andExpect(jsonPath("$.result.bufferMinutes").value(15))
                .andExpect(jsonPath("$.result.travelMode").value("CAR"))
                .andExpect(jsonPath("$.result.autoBookingEnabled").value(false));

        putAs(token, BOOKING, booking(WEEK_HOURS, "\"12:00\"", "\"13:00\"", "6", 20, "\"CAR\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.businessHours[0].openTime").value("09:00"))
                .andExpect(jsonPath("$.result.businessHours[5].closeTime").value("13:00"))
                .andExpect(jsonPath("$.result.businessHours[6].isClosed").value(true))
                .andExpect(jsonPath("$.result.lunchStart").value("12:00"))
                .andExpect(jsonPath("$.result.maxDailyVisits").value(6))
                .andExpect(jsonPath("$.result.bufferMinutes").value(20));

        // 점심 없음·한도 없음·이동수단 생략(CAR)
        putAs(token, BOOKING, booking(WEEK_HOURS.replace("\"closeTime\":\"18:00\"", "\"closeTime\":\"17:00\""),
                "null", "null", "null", 0, "null"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.businessHours[0].closeTime").value("17:00"))
                .andExpect(jsonPath("$.result.lunchStart").doesNotExist())
                .andExpect(jsonPath("$.result.maxDailyVisits").doesNotExist())
                .andExpect(jsonPath("$.result.travelMode").value("CAR"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_business_hour WHERE owner_id = ?",
                Integer.class, ownerId)).isEqualTo(7);
    }

    @Test
    @DisplayName("점심이 영업시간 밖인 요일이 있으면 422 + 그 요일들")
    void lunchOutsideBusinessHours() throws Exception {
        // 토요일은 13:00에 닫는데 점심 12:30~13:30
        putAs(token, BOOKING, booking(WEEK_HOURS, "\"12:30\"", "\"13:30\"", "null", 15, "\"CAR\""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("LUNCH_OUTSIDE_BUSINESS_HOURS"))
                .andExpect(jsonPath("$.result.daysOfWeek", hasSize(1)))
                .andExpect(jsonPath("$.result.daysOfWeek[0]").value(6));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_business_hour WHERE owner_id = ?",
                Integer.class, ownerId)).isZero();
    }

    @Test
    @DisplayName("예약 기준 - 점심 한쪽만·쉬는 날 시간·거꾸로 된 시간·같은 요일·TRANSIT·한도 0·6개 요일은 400")
    void bookingSettingsValidation() throws Exception {
        putAs(token, BOOKING, booking(WEEK_HOURS, "\"12:00\"", "null", "null", 15, "null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("lunchEnd"));
        putAs(token, BOOKING, booking(WEEK_HOURS.replace(
                "{\"dayOfWeek\":7,\"openTime\":null,\"closeTime\":null", "{\"dayOfWeek\":7,\"openTime\":\"09:00\",\"closeTime\":\"10:00\""),
                "null", "null", "null", 15, "null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("businessHours[6].openTime"));
        putAs(token, BOOKING, booking(WEEK_HOURS.replace(
                "{\"dayOfWeek\":1,\"openTime\":\"09:00\",\"closeTime\":\"18:00\"", "{\"dayOfWeek\":1,\"openTime\":\"18:00\",\"closeTime\":\"09:00\""),
                "null", "null", "null", 15, "null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("businessHours[0].closeTime"));
        putAs(token, BOOKING, booking(WEEK_HOURS.replace("{\"dayOfWeek\":2,", "{\"dayOfWeek\":1,"),
                "null", "null", "null", 15, "null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("businessHours[1].dayOfWeek"));
        putAs(token, BOOKING, booking(WEEK_HOURS, "null", "null", "null", 15, "\"TRANSIT\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("travelMode"));
        putAs(token, BOOKING, booking(WEEK_HOURS, "null", "null", "0", 15, "null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("maxDailyVisits"));
        putAs(token, BOOKING, booking("""
                [ {"dayOfWeek":1,"isClosed":true}, {"dayOfWeek":2,"isClosed":true}, {"dayOfWeek":3,"isClosed":true},
                  {"dayOfWeek":4,"isClosed":true}, {"dayOfWeek":5,"isClosed":true}, {"dayOfWeek":6,"isClosed":true} ]""",
                "null", "null", "null", 15, "null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("businessHours"));
    }

    @Test
    @DisplayName("자동 예약 켜기 - 필수 설정이 빠지면 422 + 빠진 항목, 다 채우면 켜짐")
    void autoBookingRequiresSetup() throws Exception {
        patchAs(token, AUTO_BOOKING, "{\"enabled\":true}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("AUTO_BOOKING_SETUP_INCOMPLETE"))
                .andExpect(jsonPath("$.result.missing",
                        containsInAnyOrder("BUSINESS_HOURS", "WORK_TYPE", "SERVICE_AREA", "START_ADDRESS")));

        // 작업은 제공하지만 자동 예약은 허용하지 않음 → WORK_TYPE은 아직 빠진 것
        completeSetup(token);
        putAs(token, WORK_TYPES, """
                {"workTypes":[{"workCategory":"REPAIR","isActive":true,"autoBookingAllowed":false}]}""")
                .andExpect(status().isOk());
        patchAs(token, AUTO_BOOKING, "{\"enabled\":true}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.result.missing", hasSize(1)))
                .andExpect(jsonPath("$.result.missing[0]").value("WORK_TYPE"));

        putAs(token, WORK_TYPES, """
                {"workTypes":[{"workCategory":"REPAIR","isActive":true,"autoBookingAllowed":true}]}""")
                .andExpect(status().isOk());
        patchAs(token, AUTO_BOOKING, "{\"enabled\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.autoBookingEnabled").value(true));
        getAs(token, BOOKING).andExpect(jsonPath("$.result.autoBookingEnabled").value(true));
    }

    @Test
    @DisplayName("자동 예약이 켜진 동안 필수 설정을 지우면 422로 막고 저장하지 않음 - 끈 뒤에는 바꿀 수 있음")
    void autoBookingKeepsSetup() throws Exception {
        completeSetup(token);
        patchAs(token, AUTO_BOOKING, "{\"enabled\":true}").andExpect(status().isOk());

        putAs(token, SERVICE_AREAS, "{\"serviceAreas\":[]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.result.missing[0]").value("SERVICE_AREA"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_service_area WHERE owner_id = ?",
                Integer.class, ownerId)).isEqualTo(1);

        putAs(token, BOOKING, booking(ALL_CLOSED, "null", "null", "null", 15, "null"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.result.missing[0]").value("BUSINESS_HOURS"));
        putAs(token, WORK_TYPES, "{\"workTypes\":[]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.result.missing[0]").value("WORK_TYPE"));
        putAs(token, BUSINESS, "{\"businessName\":\"새보일러\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.result.missing[0]").value("START_ADDRESS"));
        assertThat(jdbc.queryForObject("SELECT address FROM owner WHERE id = ?", String.class, ownerId)).isNotNull();

        patchAs(token, AUTO_BOOKING, "{\"enabled\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.autoBookingEnabled").value(false));
        putAs(token, SERVICE_AREAS, "{\"serviceAreas\":[]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.serviceAreas", hasSize(0)));
    }

    @Test
    @DisplayName("신규 접수 중지·재개")
    void intakePause() throws Exception {
        patchAs(token, "/api/v1/owner/intake", "{\"intakePaused\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.intakePaused").value(true));
        getAs(token, "/api/v1/owner/me").andExpect(jsonPath("$.result.intakePaused").value(true));
        patchAs(token, "/api/v1/owner/intake", "{\"intakePaused\":false}")
                .andExpect(jsonPath("$.result.intakePaused").value(false));
        patchAs(token, "/api/v1/owner/intake", "{}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("업체끼리 설정이 섞이지 않음 - 토큰의 업체 설정만 읽고 바꿈")
    void ownersAreIsolated() throws Exception {
        long otherOwnerId = accounts.approvedOwner(accounts.newPhone());
        String otherToken = accounts.ownerToken(otherOwnerId);
        completeSetup(token);

        getAs(otherToken, SERVICE_AREAS).andExpect(jsonPath("$.result.serviceAreas", hasSize(0)));
        getAs(otherToken, BUSINESS).andExpect(jsonPath("$.result.address").doesNotExist());
        getAs(otherToken, WORK_TYPES).andExpect(jsonPath("$.result.workTypes[*].isActive", hasItem(false)));
        getAs(otherToken, BOOKING).andExpect(jsonPath("$.result.businessHours[0].isClosed").value(true));

        putAs(otherToken, SERVICE_AREAS, "{\"serviceAreas\":[]}").andExpect(status().isOk());
        getAs(token, SERVICE_AREAS).andExpect(jsonPath("$.result.serviceAreas", hasSize(1)));
    }

    @Test
    @DisplayName("다른 요청이 업체 행을 잠그고 있으면 3초 뒤 503 + Retry-After (명세 1.7)")
    void settingsWriteWaitsForOwnerLock() throws Exception {
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM owner WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, ownerId);
                lock.executeQuery();
            }
            try {
                putAs(token, BOOKING, booking(WEEK_HOURS, "null", "null", "null", 15, "null"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                        .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"));
            } finally {
                holder.rollback();
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_business_hour WHERE owner_id = ?",
                Integer.class, ownerId)).isZero();
    }

    // 자동 예약 필수 설정 4가지를 모두 채움
    private void completeSetup(String ownerToken) throws Exception {
        putAs(ownerToken, BUSINESS, """
                {"businessName":"새보일러","address":"경기 고양시 덕양구 화정로 1","lat":37.634,"lng":126.832}""")
                .andExpect(status().isOk());
        putAs(ownerToken, WORK_TYPES, """
                {"workTypes":[{"workCategory":"REPAIR","isActive":true,"autoBookingAllowed":true}]}""")
                .andExpect(status().isOk());
        putAs(ownerToken, SERVICE_AREAS, """
                {"serviceAreas":[{"regionCode":"4128100000","regionName":"고양시 덕양구"}]}""")
                .andExpect(status().isOk());
        putAs(ownerToken, BOOKING, booking(WEEK_HOURS, "\"12:00\"", "\"13:00\"", "null", 15, "\"CAR\""))
                .andExpect(status().isOk());
    }

    private static String booking(String hours, String lunchStart, String lunchEnd, String maxDailyVisits,
                                  int bufferMinutes, String travelMode) {
        return "{\"businessHours\":" + hours + ",\"lunchStart\":" + lunchStart + ",\"lunchEnd\":" + lunchEnd
                + ",\"maxDailyVisits\":" + maxDailyVisits + ",\"bufferMinutes\":" + bufferMinutes
                + ",\"travelMode\":" + travelMode + "}";
    }

    private ResultActions getAs(String ownerToken, String path) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken));
    }

    private ResultActions putAs(String ownerToken, String path, String json) throws Exception {
        return mockMvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions patchAs(String ownerToken, String path, String json) throws Exception {
        return mockMvc.perform(patch(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
