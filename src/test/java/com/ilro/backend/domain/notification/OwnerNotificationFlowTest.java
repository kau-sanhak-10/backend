package com.ilro.backend.domain.notification;

import com.ilro.backend.support.TestAccounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 사장님 업무 알림 목록·읽음 (B-26, BE-14)
// 알림 행은 SQL로 직접 넣음 - 알림을 만드는 쪽(예약·확인 필요)은 각자 테스트에서 확인
@SpringBootTest
class OwnerNotificationFlowTest {

    private static final String LIST = "/api/v1/owner/notifications";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private long ownerId;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        accounts = new TestAccounts(jdbc);
        ownerId = accounts.approvedOwner(accounts.newPhone());
        ownerToken = accounts.ownerToken(ownerId);
    }

    @AfterEach
    void tearDown() {
        accounts.cleanUp();
    }

    @Test
    @DisplayName("목록 - 자기 업체의 앱 안 알림만 최근 것부터, 푸시 행·고객 알림·다른 업체 알림은 빠짐")
    void list() throws Exception {
        long older = notification(ownerId, "OWNER", "NEEDS_CHECK", "INQUIRY", 11L, "IN_APP", false);
        long newer = notification(ownerId, "OWNER", "RESERVATION_CONFIRMED", "RESERVATION", 21L, "IN_APP", true);
        notification(ownerId, "OWNER", "RESERVATION_CONFIRMED", "RESERVATION", 21L, "WEB_PUSH", false);
        notification(ownerId, "CUSTOMER", "RESERVATION_CONFIRMED", "RESERVATION", 21L, "SMS", false);
        long otherOwner = accounts.approvedOwner(accounts.newPhone());
        notification(otherOwner, "OWNER", "NEEDS_CHECK", "INQUIRY", 31L, "IN_APP", false);

        getAs(ownerToken, LIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[*].notificationId", contains((int) newer, (int) older)))
                .andExpect(jsonPath("$.result.content[0].eventType").value("RESERVATION_CONFIRMED"))
                .andExpect(jsonPath("$.result.content[0].refType").value("RESERVATION"))
                .andExpect(jsonPath("$.result.content[0].refId").value(21))
                .andExpect(jsonPath("$.result.content[1].readAt").value(nullValue()))
                .andExpect(jsonPath("$.result.totalElements").value(2))
                .andExpect(jsonPath("$.result.page").value(0))
                .andExpect(jsonPath("$.result.size").value(20))
                .andExpect(jsonPath("$.result.isLast").value(true));
    }

    @Test
    @DisplayName("목록 - unread=true면 안 읽은 것만")
    void unreadOnly() throws Exception {
        long unread = notification(ownerId, "OWNER", "NEEDS_CHECK", "INQUIRY", 11L, "IN_APP", false);
        notification(ownerId, "OWNER", "CHANGE", "RESERVATION", 21L, "IN_APP", true);

        getAs(ownerToken, LIST + "?unread=true")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.content[0].notificationId").value(unread));
        getAs(ownerToken, LIST + "?unread=false")
                .andExpect(jsonPath("$.result.content", hasSize(2)));
    }

    @Test
    @DisplayName("목록 - 쪽 나눔, 범위 밖 page·size는 400")
    void paging() throws Exception {
        for (int i = 0; i < 3; i++) {
            notification(ownerId, "OWNER", "NEEDS_CHECK", "INQUIRY", 100L + i, "IN_APP", false);
        }

        getAs(ownerToken, LIST + "?size=2")
                .andExpect(jsonPath("$.result.content", hasSize(2)))
                .andExpect(jsonPath("$.result.totalPages").value(2))
                .andExpect(jsonPath("$.result.isLast").value(false));
        getAs(ownerToken, LIST + "?size=2&page=1")
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.isLast").value(true));

        getAs(ownerToken, LIST + "?size=100").andExpect(status().isOk());
        getAs(ownerToken, LIST + "?size=101&page=-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result[*].field", contains("page", "size")));
    }

    @Test
    @DisplayName("읽음 - 읽은 시각이 남고, 두 번 보내도 처음 시각 그대로 200")
    void read() throws Exception {
        long id = notification(ownerId, "OWNER", "NEEDS_CHECK", "INQUIRY", 11L, "IN_APP", false);

        postAs(ownerToken, LIST + "/" + id + "/read")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.notificationId").value(id))
                .andExpect(jsonPath("$.result.readAt").isNotEmpty());
        Timestamp firstRead = readAt(id);
        assertThat(firstRead).isNotNull();

        postAs(ownerToken, LIST + "/" + id + "/read").andExpect(status().isOk());
        assertThat(readAt(id)).isEqualTo(firstRead);

        getAs(ownerToken, LIST + "?unread=true").andExpect(jsonPath("$.result.content", hasSize(0)));
    }

    @Test
    @DisplayName("읽음 - 다른 업체 알림·푸시 행·없는 알림은 404, 읽음 표시도 안 남음")
    void readNotFound() throws Exception {
        long otherOwner = accounts.approvedOwner(accounts.newPhone());
        long others = notification(otherOwner, "OWNER", "NEEDS_CHECK", "INQUIRY", 11L, "IN_APP", false);
        long push = notification(ownerId, "OWNER", "NEEDS_CHECK", "INQUIRY", 11L, "WEB_PUSH", false);

        for (long id : new long[]{others, push, Long.MAX_VALUE}) {
            postAs(ownerToken, LIST + "/" + id + "/read")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
        }
        assertThat(readAt(others)).isNull();
        assertThat(readAt(push)).isNull();
    }

    @Test
    @DisplayName("사장님 토큰이 없으면 401")
    void requiresOwnerToken() throws Exception {
        mockMvc.perform(get(LIST)).andExpect(status().isUnauthorized());
    }

    // ref_id는 FK가 없어서 아무 번호나 넣어도 됨
    private long notification(long owner, String recipient, String eventType, String refType, long refId,
                              String channel, boolean read) {
        return jdbc.queryForObject("INSERT INTO notification (owner_id, recipient, event_type, ref_type, ref_id, "
                        + "channel, dedupe_key, read_at) VALUES (?, ?, ?, ?, ?, ?, ?, "
                        + (read ? "now()" : "null") + ") RETURNING id",
                Long.class, owner, recipient, eventType, refType, refId, channel, "test-" + UUID.randomUUID());
    }

    private Timestamp readAt(long id) {
        return jdbc.queryForObject("SELECT read_at FROM notification WHERE id = ?", Timestamp.class, id);
    }

    private ResultActions getAs(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions postAs(String token, String path) throws Exception {
        return mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }
}