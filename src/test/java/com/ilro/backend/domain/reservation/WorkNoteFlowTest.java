package com.ilro.backend.domain.reservation;

import com.ilro.backend.domain.reservation.dto.WorkNoteRequest;
import com.ilro.backend.domain.reservation.service.WorkNoteService;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 사장님 작업 메모 (B-24, 명세 1.8·4.6, BE-13)
@SpringBootTest
class WorkNoteFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkNoteService workNoteService;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private long ownerId;
    private String ownerToken;
    private String customerToken;
    private long reservationId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        accounts = new TestAccounts(jdbc);
        ownerId = accounts.approvedOwner(accounts.newPhone());
        ownerToken = accounts.ownerToken(ownerId);
        String phone = accounts.newPhone();
        customerToken = accounts.token("CUSTOMER", ownerId, null, phone, "RESERVATION_LOOKUP",
                "CURRENT_TIMESTAMP + interval '1 day'");
        long customerId = jdbc.queryForObject("INSERT INTO customer (owner_id, name, phone) VALUES (?, '김고객', ?) "
                + "RETURNING id", Long.class, ownerId, phone);
        OffsetDateTime startAt = OffsetDateTime.parse("2099-01-05T10:00:00+09:00");
        reservationId = jdbc.queryForObject("INSERT INTO reservation (owner_id, customer_id, start_at, end_at, status) "
                + "VALUES (?, ?, ?, ?, 'CONFIRMED') RETURNING id", Long.class, ownerId, customerId, startAt,
                startAt.plusHours(1));
    }

    @AfterEach
    void tearDown() {
        accounts.cleanUp();
    }

    @Test
    @DisplayName("메모가 없으면 빈 내용, 저장·수정해도 한 줄, 빈 문자열이면 비움, 예약 버전은 그대로")
    void saveAndReplace() throws Exception {
        getNote(ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.reservationId").value((int) reservationId))
                .andExpect(jsonPath("$.result.content").value(""))
                .andExpect(jsonPath("$.result.updatedAt").value(nullValue()));

        putNote(ownerToken, "{\"content\":\"  배기통 점검 필요  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content").value("배기통 점검 필요"))
                .andExpect(jsonPath("$.result.updatedAt").value(notNullValue()));
        putNote(ownerToken, "{\"content\":\"배기통 교체함\"}").andExpect(status().isOk());
        getNote(ownerToken).andExpect(jsonPath("$.result.content").value("배기통 교체함"));
        assertThat(noteCount()).isEqualTo(1);

        putNote(ownerToken, "{\"content\":\"\"}").andExpect(status().isOk());
        getNote(ownerToken).andExpect(jsonPath("$.result.content").value(""));

        assertThat(jdbc.queryForObject("SELECT version FROM reservation WHERE id = ?", Integer.class,
                reservationId)).isZero();
    }

    @Test
    @DisplayName("완료·취소된 예약에도 메모를 남길 수 있음")
    void anyStatus() throws Exception {
        for (String status : List.of("COMPLETED", "CANCELED")) {
            jdbc.update("UPDATE reservation SET status = ? WHERE id = ?", status, reservationId);
            putNote(ownerToken, "{\"content\":\"" + status + "\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.content").value(status));
        }
    }

    @Test
    @DisplayName("내용이 없거나 2000자를 넘으면 400, 2000자는 저장")
    void validation() throws Exception {
        for (String body : List.of("{}", "{\"content\":\"" + "a".repeat(2001) + "\"}")) {
            putNote(ownerToken, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.result[0].field").value("content"));
        }
        assertThat(noteCount()).isZero();

        putNote(ownerToken, "{\"content\":\"" + "a".repeat(2000) + "\"}").andExpect(status().isOk());
    }

    @Test
    @DisplayName("다른 업체 예약의 메모는 읽지도 쓰지도 못함 (404)")
    void otherOwner() throws Exception {
        putNote(ownerToken, "{\"content\":\"원래 메모\"}").andExpect(status().isOk());
        String otherToken = accounts.ownerToken(accounts.approvedOwner(accounts.newPhone()));

        getNote(otherToken)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        putNote(otherToken, "{\"content\":\"덮어쓰기\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));

        getNote(ownerToken).andExpect(jsonPath("$.result.content").value("원래 메모"));
    }

    @Test
    @DisplayName("고객은 메모 API를 쓸 수 없고, 고객 예약 상세에도 메모가 나오지 않음")
    void hiddenFromCustomer() throws Exception {
        putNote(ownerToken, "{\"content\":\"OWNER-ONLY-NOTE\"}").andExpect(status().isOk());

        getNote(customerToken).andExpect(status().is4xxClientError());
        putNote(customerToken, "{\"content\":\"덮어쓰기\"}").andExpect(status().is4xxClientError());
        mockMvc.perform(get("/api/v1/customer/reservations/" + reservationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("OWNER-ONLY-NOTE"))));

        getNote(ownerToken).andExpect(jsonPath("$.result.content").value("OWNER-ONLY-NOTE"));
    }

    @Test
    @DisplayName("첫 저장이 동시에 와도 모두 성공하고 메모는 한 줄")
    void concurrentFirstSave() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String text = "메모 " + i;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return workNoteService.save(ownerId, reservationId, new WorkNoteRequest(text));
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> result : results) {
                result.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(noteCount()).isEqualTo(1);
    }

    private ResultActions getNote(String token) throws Exception {
        return mockMvc.perform(get(path()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions putNote(String token, String body) throws Exception {
        return mockMvc.perform(put(path()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String path() {
        return "/api/v1/owner/reservations/" + reservationId + "/work-note";
    }

    private int noteCount() {
        return jdbc.queryForObject("SELECT count(*) FROM work_note WHERE reservation_id = ?", Integer.class,
                reservationId);
    }
}
