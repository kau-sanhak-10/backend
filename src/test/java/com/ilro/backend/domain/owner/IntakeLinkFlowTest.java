package com.ilro.backend.domain.owner;

import com.ilro.backend.domain.owner.service.IntakeLinkTokens;
import com.ilro.backend.support.TestAccounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 접수 링크 조회·발급 (BE-04, B-11·O-04) - 실제 토큰으로 호출
@SpringBootTest
class IntakeLinkFlowTest {

    private static final String OWNER_LINK = "/api/v1/owner/intake-link";
    private static final String BASE_URL = "http://localhost:3000/customer/";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoSpyBean
    private IntakeLinkTokens tokens;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private long ownerId;
    private String ownerToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        accounts = new TestAccounts(jdbc);
        ownerId = accounts.approvedOwner(accounts.newPhone());
        ownerToken = accounts.ownerToken(ownerId);
        long adminId = accounts.admin(accounts.newPhone());
        adminToken = accounts.token("ADMIN", null, adminId, null, null, "CURRENT_TIMESTAMP + interval '1 day'");
    }

    @AfterEach
    void tearDown() {
        accounts.cleanUp();
    }

    @Test
    @DisplayName("사장님은 자기 업체의 접수 링크를 받고, 조회해도 토큰은 바뀌지 않는다")
    void ownerGetsOwnLink() throws Exception {
        String token = linkToken(ownerId);

        mockMvc.perform(get(OWNER_LINK).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("READY"))
                .andExpect(jsonPath("$.result.url").value(BASE_URL + token))
                .andExpect(jsonPath("$.result.businessName").value("테스트보일러"));

        assertThat(linkToken(ownerId)).isEqualTo(token);
    }

    @Test
    @DisplayName("다른 업체의 링크는 받을 수 없다 - 업체는 로그인 토큰으로만 정해짐")
    void linksAreSeparatedByOwner() throws Exception {
        long otherId = accounts.approvedOwner(accounts.newPhone());
        String otherToken = accounts.ownerToken(otherId);

        mockMvc.perform(get(OWNER_LINK).header(HttpHeaders.AUTHORIZATION, bearer(otherToken)))
                .andExpect(jsonPath("$.result.url").value(BASE_URL + linkToken(otherId)));
        assertThat(linkToken(otherId)).isNotEqualTo(linkToken(ownerId));
    }

    @Test
    @DisplayName("토큰 없이 조회하면 401")
    void ownerLinkRequiresLogin() throws Exception {
        mockMvc.perform(get(OWNER_LINK)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("운영자가 재발급하면 토큰이 바뀌고, 사장님도 새 링크를 받는다")
    void adminReissueReplacesToken() throws Exception {
        String oldToken = linkToken(ownerId);

        mockMvc.perform(post(adminLink(ownerId)).header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("READY"));

        String newToken = linkToken(ownerId);
        assertThat(newToken).isNotEqualTo(oldToken).hasSize(43).matches("[A-Za-z0-9_-]+");
        mockMvc.perform(get(OWNER_LINK).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(jsonPath("$.result.url").value(BASE_URL + newToken));
    }

    @Test
    @DisplayName("승인 전 업체에는 발급할 수 없다 - 409, 토큰도 그대로")
    void pendingOwnerCannotBeIssued() throws Exception {
        long pendingId = accounts.owner(accounts.newPhone(), "PENDING", "READY", null);
        String token = linkToken(pendingId);

        mockMvc.perform(post(adminLink(pendingId)).header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNER_NOT_APPROVED"));

        assertThat(linkToken(pendingId)).isEqualTo(token);
    }

    @Test
    @DisplayName("없는 업체는 404")
    void unknownOwnerIsNotFound() throws Exception {
        mockMvc.perform(post(adminLink(Long.MAX_VALUE)).header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("사장님 토큰으로는 발급할 수 없다 - 403")
    void ownerCannotIssue() throws Exception {
        String oldToken = linkToken(ownerId);

        mockMvc.perform(post(adminLink(ownerId)).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isForbidden());

        assertThat(linkToken(ownerId)).isEqualTo(oldToken);
    }

    @Test
    @DisplayName("새 토큰이 다른 업체와 겹치면 다시 만들어 저장한다")
    void regeneratesWhenTokenCollides() throws Exception {
        long otherId = accounts.approvedOwner(accounts.newPhone());
        String taken = linkToken(otherId);
        doReturn(taken).doCallRealMethod().when(tokens).generate();

        mockMvc.perform(post(adminLink(ownerId)).header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk());

        assertThat(linkToken(ownerId)).isNotEqualTo(taken);
        assertThat(linkToken(otherId)).isEqualTo(taken);
    }

    private String linkToken(long id) {
        return jdbc.queryForObject("SELECT link_token FROM owner WHERE id = ?", String.class, id);
    }

    private static String adminLink(long id) {
        return "/api/v1/admin/owners/" + id + "/intake-link";
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}