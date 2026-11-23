package com.ilro.backend.domain.owner;

import com.ilro.backend.domain.inquiry.service.IntakeTerms;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 사장님 탈퇴 요청 (B-04, 명세 3.2·4.4·5.1, BE-16) - 다시 인증, 남은 문의·예약이면 막음, 기록은 지우지 않음
@SpringBootTest
class OwnerWithdrawalFlowTest {

    private static final String PATH = "/api/v1/owner/withdrawal-requests";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private IntakeTerms intakeTerms;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private String phone;
    private long ownerId;
    private String ownerToken;
    private long customerId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        accounts = new TestAccounts(jdbc);
        phone = accounts.newPhone();
        ownerId = accounts.approvedOwner(phone);
        ownerToken = accounts.ownerToken(ownerId);
        jdbc.update("UPDATE owner SET auto_booking_enabled = true WHERE id = ?", ownerId);
        customerId = jdbc.queryForObject("INSERT INTO customer (owner_id, name, phone) VALUES (?, '김고객', ?) "
                + "RETURNING id", Long.class, ownerId, accounts.newPhone());
    }

    @AfterEach
    void tearDown() {
        accounts.cleanUp();
    }

    @Test
    @DisplayName("다시 인증 - 방금(10분 안) 이 업체 번호로 마친 사장님 로그인 인증만, 아니면 탈퇴하지 않음")
    void reauth() throws Exception {
        withdraw(ownerToken, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        withdraw(ownerToken, "{\"verificationId\":999999999}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VERIFICATION_NOT_FOUND"));
        // 다른 업체의 인증은 없는 것처럼
        long otherOwner = accounts.approvedOwner(accounts.newPhone());
        withdraw(ownerToken, "{\"verificationId\":" + verification("OWNER_LOGIN", otherOwner, "1 minute") + "}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VERIFICATION_NOT_FOUND"));
        // 인증번호를 아직 확인하지 않음 / 11분 전 인증 / 다른 목적의 인증
        for (long verificationId : new long[]{verification("OWNER_LOGIN", ownerId, null),
                verification("OWNER_LOGIN", ownerId, "11 minutes"),
                verification("INQUIRY_ACCESS", ownerId, "1 minute")}) {
            withdraw(ownerToken, "{\"verificationId\":" + verificationId + "}")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("VERIFICATION_PURPOSE_MISMATCH"));
        }
        // 인증한 뒤 로그인 번호가 바뀜
        long beforeChange = verification("OWNER_LOGIN", ownerId, "1 minute");
        jdbc.update("UPDATE owner SET phone = ? WHERE id = ?", accounts.newPhone(), ownerId);
        withdraw(ownerToken, "{\"verificationId\":" + beforeChange + "}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("VERIFICATION_PURPOSE_MISMATCH"));

        assertThat(jdbc.queryForObject("SELECT status FROM owner WHERE id = ?", String.class, ownerId))
                .isEqualTo("ACTIVE");
        me(ownerToken).andExpect(status().isOk());
    }

    @Test
    @DisplayName("남은 문의·예약이 있으면 422 WITHDRAWAL_BLOCKED(개수), 정리하면 WITHDRAWN - 기록은 그대로, 토큰·로그인 막힘")
    void withdraw() throws Exception {
        long open = inquiry("COLLECTING", false);
        inquiry("OWNER_CHECK", true);
        inquiry("BOOKED", false);
        inquiry("UNSERVICEABLE", false);
        inquiry("CLOSED", false);
        long confirmed = reservation("CONFIRMED", 7);
        reservation("COMPLETED", -7);
        reservation("CANCELED", 3);
        long verificationId = verification("OWNER_LOGIN", ownerId, "1 minute");
        String body = "{\"verificationId\":" + verificationId + "}";

        withdraw(ownerToken, body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("WITHDRAWAL_BLOCKED"))
                .andExpect(jsonPath("$.result.openInquiries").value(1))
                .andExpect(jsonPath("$.result.activeReservations").value(1));
        jdbc.update("UPDATE inquiry SET status = 'CLOSED' WHERE id = ?", open);
        withdraw(ownerToken, body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.result.openInquiries").value(0))
                .andExpect(jsonPath("$.result.activeReservations").value(1));
        assertThat(jdbc.queryForObject("SELECT status FROM owner WHERE id = ?", String.class, ownerId))
                .isEqualTo("ACTIVE");
        me(ownerToken).andExpect(status().isOk());

        // 방문을 마쳐 완료 처리하면 탈퇴 가능 - 예약 취소와 다름 (예약·문의 기록은 지우거나 바꾸지 않음)
        jdbc.update("UPDATE reservation SET status = 'COMPLETED' WHERE id = ?", confirmed);
        withdraw(ownerToken, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.ownerId").value((int) ownerId))
                .andExpect(jsonPath("$.result.status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.result.withdrawnAt", notNullValue()));
        assertThat(jdbc.queryForMap("SELECT status, auto_booking_enabled FROM owner WHERE id = ?", ownerId))
                .containsEntry("status", "WITHDRAWN").containsEntry("auto_booking_enabled", false);
        assertThat(jdbc.queryForList("SELECT status FROM reservation WHERE owner_id = ? ORDER BY id", String.class,
                ownerId)).containsExactly("COMPLETED", "COMPLETED", "CANCELED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inquiry WHERE owner_id = ?", Integer.class, ownerId))
                .isEqualTo(5);

        // 로그인 토큰은 모두 끊기고, 새로 받아도 탈퇴 계정이라 403, 로그인 문자도 보내지 않음
        me(ownerToken).andExpect(status().isUnauthorized());
        String fresh = accounts.ownerToken(ownerId);
        me(fresh)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWNER_WITHDRAWN"));
        withdraw(fresh, body)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWNER_WITHDRAWN"));
        // 방금 넣은 인증 기록 때문에 재발송 간격(60초)에 걸리지 않게 과거로
        jdbc.update("UPDATE phone_verification SET created_at = created_at - interval '2 minutes' WHERE phone = ?",
                phone);
        String ip = TestAccounts.newIp();
        mockMvc.perform(post("/api/v1/auth/verifications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"OWNER_LOGIN\",\"phone\":\"" + phone + "\"}")
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        }))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWNER_WITHDRAWN"));
    }

    @Test
    @DisplayName("탈퇴와 새 접수가 겹침 - 접수는 탈퇴가 끝난 뒤에 처리돼, 탈퇴한 업체에 진행 중 상담이 생기지 않음")
    void intakeDuringWithdrawal() throws Exception {
        long verificationId = verification("OWNER_LOGIN", ownerId, "1 minute");
        String linkToken = accounts.linkToken(ownerId);
        String start = "{\"consents\":[{\"type\":\"PRIVACY_REQUIRED\",\"termsVersion\":\"" + intakeTerms.version()
                + "\"}]}";

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // 탈퇴가 아직 끝나지 않은(커밋 전) 사이에 고객의 새 접수가 들어옴
            Future<MvcResult> intake = transactionTemplate.execute(tx -> {
                try {
                    withdraw(ownerToken, "{\"verificationId\":" + verificationId + "}")
                            .andExpect(status().isOk());
                    Future<MvcResult> starting = executor.submit(() -> mockMvc.perform(
                            post("/api/v1/public/intake/" + linkToken + "/inquiries")
                                    .contentType(MediaType.APPLICATION_JSON).content(start)).andReturn());
                    // 접수의 업체 조회가 탈퇴가 잡은 잠금을 기다리는 것을 DB에서 확인한 뒤에 탈퇴를 끝냄
                    // (시간만 기다리면 접수가 늦게 시작해 탈퇴 뒤에 처리돼도 통과함 - 겹쳤다는 증거가 되지 않음)
                    boolean waitingOnOwnerLock = false;
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
                    while (!waitingOnOwnerLock && !starting.isDone() && System.nanoTime() < deadline) {
                        // 이 트랜잭션(탈퇴)이 끝나기를 기다리는 다른 연결의 조회(select ... for update)가 있는지
                        waitingOnOwnerLock = jdbc.queryForObject("SELECT count(*) FROM pg_locks l "
                                + "JOIN pg_stat_activity a ON a.pid = l.pid WHERE NOT l.granted "
                                + "AND l.locktype = 'transactionid' AND l.transactionid = pg_current_xact_id()::xid "
                                + "AND a.query ILIKE 'select%'", Integer.class) > 0;
                        if (!waitingOnOwnerLock) {
                            Thread.sleep(20);
                        }
                    }
                    assertThat(waitingOnOwnerLock).as("접수의 업체 조회가 탈퇴의 잠금을 기다려야 함").isTrue();
                    return starting;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            MvcResult result = intake.get(10, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString())
                    .isBetween(400, 499);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inquiry WHERE owner_id = ?", Integer.class,
                    ownerId)).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM owner WHERE id = ?", String.class, ownerId))
                    .isEqualTo("WITHDRAWN");
        } finally {
            executor.shutdownNow();
        }
    }

    // 그 업체 로그인 번호로 받은 문자 인증 - verifiedAgo가 null이면 아직 인증 전
    private long verification(String purpose, long targetOwnerId, String verifiedAgo) {
        return jdbc.queryForObject("INSERT INTO phone_verification (phone, purpose, code_hash, send_status, "
                + "expires_at, verified_at, target_owner_id) VALUES (?, ?, 'seeded', 'SENT', "
                + "CURRENT_TIMESTAMP + interval '5 minutes', "
                + "CASE WHEN ?::text IS NULL THEN NULL ELSE CURRENT_TIMESTAMP - ?::interval END, ?) RETURNING id",
                Long.class, phone, purpose, verifiedAgo, verifiedAgo, targetOwnerId);
    }

    private long inquiry(String status, boolean deleted) {
        return jdbc.queryForObject("INSERT INTO inquiry (owner_id, status, deleted_at) VALUES (?, ?, "
                + "CASE WHEN ? THEN CURRENT_TIMESTAMP END) RETURNING id", Long.class, ownerId, status, deleted);
    }

    private long reservation(String status, int daysFromNow) {
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(daysFromNow).withHour(10)
                .withMinute(0).withSecond(0).withNano(0);
        return jdbc.queryForObject("INSERT INTO reservation (owner_id, customer_id, start_at, end_at, address, "
                + "status, work_minutes, buffer_minutes, travel_time_minutes) "
                + "VALUES (?, ?, ?, ?, '경기 고양시 덕양구 화정로 1', ?, 60, 15, 0) RETURNING id", Long.class,
                ownerId, customerId, start, start.plusHours(1), status);
    }

    private ResultActions withdraw(String token, String body) throws Exception {
        return mockMvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions me(String token) throws Exception {
        return mockMvc.perform(get("/api/v1/owner/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }
}
