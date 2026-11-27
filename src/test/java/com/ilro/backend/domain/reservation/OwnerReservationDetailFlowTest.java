package com.ilro.backend.domain.reservation;

import com.ilro.backend.support.TestAccounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 사장님 방문 작업정보 (B-23, 명세 1.8·4.6, BE-13)
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class OwnerReservationDetailFlowTest {

    private static final OffsetDateTime START = OffsetDateTime.parse("2026-01-05T10:00:00+09:00");
    private static final String ADDRESS = "경기 고양시 덕양구 화정로 1";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private long ownerId;
    private String ownerToken;
    private String phone;
    private long customerId;
    private long workTypeId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        accounts = new TestAccounts(jdbc);
        ownerId = accounts.approvedOwner(accounts.newPhone());
        ownerToken = accounts.ownerToken(ownerId);
        phone = accounts.newPhone();
        customerId = jdbc.queryForObject("INSERT INTO customer (owner_id, name, phone) VALUES (?, '김고객', ?) "
                + "RETURNING id", Long.class, ownerId, phone);
        workTypeId = jdbc.queryForObject("INSERT INTO owner_work_type (owner_id, work_category, duration_minutes, "
                + "is_active, auto_booking_allowed) VALUES (?, 'REPAIR', 60, true, true) RETURNING id", Long.class,
                ownerId);
    }

    @AfterEach
    void tearDown() {
        accounts.cleanUp();
    }

    @Test
    @DisplayName("문의에서 잡은 예약 - 예약·고객·상세주소·출입 메모, 접근 기록에는 번호를 남기지 않음")
    void fromInquiry(CapturedOutput output) throws Exception {
        long inquiryId = inquiry();
        long id = reservation(inquiryId, "CONFIRMED");

        getDetail(ownerToken, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.reservationId").value((int) id))
                .andExpect(jsonPath("$.result.inquiryId").value((int) inquiryId))
                .andExpect(jsonPath("$.result.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.result.version").value(0))
                .andExpect(jsonPath("$.result.startAt").value("2026-01-05T10:00:00+09:00"))
                .andExpect(jsonPath("$.result.endAt").value("2026-01-05T11:00:00+09:00"))
                .andExpect(jsonPath("$.result.workMinutes").value(60))
                .andExpect(jsonPath("$.result.workCategory").value("REPAIR"))
                .andExpect(jsonPath("$.result.isManual").value(false))
                .andExpect(jsonPath("$.result.customerName").value("김고객"))
                .andExpect(jsonPath("$.result.customerPhone").value(phone))
                .andExpect(jsonPath("$.result.address").value(ADDRESS))
                .andExpect(jsonPath("$.result.addressDetail").value("101동 202호"))
                .andExpect(jsonPath("$.result.lat").value(37.634))
                .andExpect(jsonPath("$.result.lng").value(126.832))
                .andExpect(jsonPath("$.result.accessNote").value("지하 주차 가능"))
                .andExpect(jsonPath("$.result.completedAt").value(nullValue()))
                .andExpect(jsonPath("$.result.activeChange").value(nullValue()));

        assertThat(output.getAll()).contains("actor=OWNER:" + ownerId + " action=VIEW target=reservation:" + id);
        assertThat(output.getAll().lines().filter(line -> line.contains("target=reservation:" + id)))
                .noneMatch(line -> line.contains(phone) || line.contains(ADDRESS));
    }

    @Test
    @DisplayName("수동 예약 - 문의가 없으므로 inquiryId·상세주소·출입 메모가 null")
    void manual() throws Exception {
        long id = reservation(null, "CONFIRMED");
        jdbc.update("UPDATE reservation SET is_manual = true WHERE id = ?", id);

        getDetail(ownerToken, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isManual").value(true))
                .andExpect(jsonPath("$.result.customerPhone").value(phone))
                .andExpect(jsonPath("$.result.address").value(ADDRESS));
        assertNoInquiryInfo(id);
        getDetail(ownerToken, id).andExpect(jsonPath("$.result.inquiryId").value(nullValue()));
    }

    @Test
    @DisplayName("고객·작업 종류가 없는 예약도 볼 수 있음")
    void withoutCustomer() throws Exception {
        long id = reservation(null, "CONFIRMED");
        jdbc.update("UPDATE reservation SET customer_id = NULL, work_type_id = NULL WHERE id = ?", id);

        getDetail(ownerToken, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.customerName").value(nullValue()))
                .andExpect(jsonPath("$.result.customerPhone").value(nullValue()))
                .andExpect(jsonPath("$.result.workCategory").value(nullValue()));
    }

    @Test
    @DisplayName("예약 주소가 바뀌면 문의에 적은 옛집 상세주소·출입 메모는 주지 않음")
    void changedAddress() throws Exception {
        long inquiryId = inquiry();
        long id = reservation(inquiryId, "CONFIRMED");
        jdbc.update("UPDATE reservation SET address = '경기 고양시 덕양구 ○○로 34' WHERE id = ?", id);

        getDetail(ownerToken, id)
                .andExpect(jsonPath("$.result.inquiryId").value((int) inquiryId))
                .andExpect(jsonPath("$.result.address").value("경기 고양시 덕양구 ○○로 34"));
        assertNoInquiryInfo(id);
    }

    @Test
    @DisplayName("삭제된 문의 - 문의 번호·상세주소·출입 메모는 주지 않고 예약은 보임")
    void deletedInquiry() throws Exception {
        long inquiryId = inquiry();
        long id = reservation(inquiryId, "CONFIRMED");
        jdbc.update("UPDATE inquiry SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", inquiryId);

        getDetail(ownerToken, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.inquiryId").value(nullValue()))
                .andExpect(jsonPath("$.result.address").value(ADDRESS));
        assertNoInquiryInfo(id);
    }

    @Test
    @DisplayName("완료 시각 - 마지막 완료 이력의 시각, 정정해서 확정으로 돌아가면 null")
    void completedAt() throws Exception {
        long id = reservation(null, "COMPLETED");
        history(id, "CONFIRMED", "COMPLETED", "2026-01-05T11:00:00+09:00");
        history(id, "COMPLETED", "CONFIRMED", "2026-01-05T11:10:00+09:00");
        history(id, "CONFIRMED", "COMPLETED", "2026-01-05T12:00:00+09:00");

        getDetail(ownerToken, id).andExpect(jsonPath("$.result.completedAt").value("2026-01-05T12:00:00+09:00"));

        jdbc.update("UPDATE reservation SET status = 'CONFIRMED' WHERE id = ?", id);
        getDetail(ownerToken, id).andExpect(jsonPath("$.result.completedAt").value(nullValue()));
    }

    @Test
    @DisplayName("진행 중 변경은 보이고, 기한이 지난 제안은 보이지 않음")
    void activeChange() throws Exception {
        long id = reservation(null, "CONFIRMED");
        long changeId = jdbc.queryForObject("INSERT INTO reservation_change (reservation_id, change_type, "
                + "requested_by, prev_start_at, prev_end_at, proposed_start_at, proposed_end_at, status, expires_at, "
                + "base_version) VALUES (?, 'CHANGE', 'OWNER', ?, ?, ?, ?, 'PROPOSED', "
                + "CURRENT_TIMESTAMP + interval '1 hour', 0) RETURNING id", Long.class, id, START, START.plusHours(1),
                START.plusDays(1), START.plusDays(1).plusHours(1));

        getDetail(ownerToken, id)
                .andExpect(jsonPath("$.result.activeChange.changeId").value((int) changeId))
                .andExpect(jsonPath("$.result.activeChange.status").value("PROPOSED"));

        jdbc.update("UPDATE reservation_change SET expires_at = CURRENT_TIMESTAMP - interval '1 minute' WHERE id = ?",
                changeId);
        getDetail(ownerToken, id).andExpect(jsonPath("$.result.activeChange").value(nullValue()));
    }

    @Test
    @DisplayName("다른 업체·없는 예약은 404, 고객 토큰은 쓸 수 없음")
    void denied() throws Exception {
        long id = reservation(null, "CONFIRMED");
        String otherToken = accounts.ownerToken(accounts.approvedOwner(accounts.newPhone()));
        String customerToken = accounts.token("CUSTOMER", ownerId, null, phone, "RESERVATION_LOOKUP",
                "CURRENT_TIMESTAMP + interval '1 day'");

        getDetail(otherToken, id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        getDetail(ownerToken, Long.MAX_VALUE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        getDetail(customerToken, id).andExpect(status().is4xxClientError());
    }

    private void assertNoInquiryInfo(long id) throws Exception {
        getDetail(ownerToken, id)
                .andExpect(jsonPath("$.result.addressDetail").value(nullValue()))
                .andExpect(jsonPath("$.result.accessNote").value(nullValue()));
    }

    private ResultActions getDetail(String token, long id) throws Exception {
        return mockMvc.perform(get("/api/v1/owner/reservations/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private long inquiry() {
        return jdbc.queryForObject("INSERT INTO inquiry (owner_id, customer_id, status, address, address_detail, "
                + "access_note) VALUES (?, ?, 'BOOKED', ?, '101동 202호', '지하 주차 가능') RETURNING id", Long.class,
                ownerId, customerId, ADDRESS);
    }

    private long reservation(Long inquiryId, String status) {
        return jdbc.queryForObject("INSERT INTO reservation (inquiry_id, owner_id, customer_id, start_at, end_at, "
                + "address, lat, lng, status, work_type_id, work_minutes) "
                + "VALUES (?, ?, ?, ?, ?, ?, 37.634, 126.832, ?, ?, 60) RETURNING id", Long.class, inquiryId, ownerId,
                customerId, START, START.plusHours(1), ADDRESS, status, workTypeId);
    }

    private void history(long id, String from, String to, String createdAt) {
        jdbc.update("INSERT INTO reservation_history (reservation_id, from_status, to_status, changed_by, created_at) "
                + "VALUES (?, ?, ?, 'OWNER', ?)", id, from, to, OffsetDateTime.parse(createdAt));
    }
}
