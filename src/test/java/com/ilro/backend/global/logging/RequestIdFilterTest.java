package com.ilro.backend.global.logging;

import com.ilro.backend.global.auth.AuthInterceptor;
import com.ilro.backend.support.TestAccounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 요청 번호(X-Request-Id)와 로그 메모장(MDC) 정리 - 실제 필터·인터셉터로 호출
@SpringBootTest
class RequestIdFilterTest {

    private static final String OWNER_LINK = "/api/v1/owner/intake-link";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RequestIdFilter requestIdFilter;

    private MockMvc mockMvc;
    private TestAccounts accounts;

    @BeforeEach
    void setUp() {
        // webAppContextSetup은 필터를 자동으로 안 붙여서 직접 추가
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
        accounts = new TestAccounts(jdbc);
    }

    @AfterEach
    void tearDown() {
        accounts.cleanUp();
    }

    @Test
    @DisplayName("인증에 실패한 요청에도 요청 번호가 붙고, 요청마다 번호가 다르다")
    void everyResponseHasDifferentRequestId() throws Exception {
        String first = mockMvc.perform(get(OWNER_LINK))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getHeader(RequestIdFilter.HEADER);
        String second = mockMvc.perform(get(OWNER_LINK))
                .andReturn().getResponse().getHeader(RequestIdFilter.HEADER);

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank().isNotEqualTo(first);
    }

    @Test
    @DisplayName("클라이언트가 보낸 요청 번호는 쓰지 않고 서버가 새로 만든다")
    void ignoresClientRequestId() throws Exception {
        String requestId = mockMvc.perform(get(OWNER_LINK).header(RequestIdFilter.HEADER, "client-made-id"))
                .andReturn().getResponse().getHeader(RequestIdFilter.HEADER);

        assertThat(requestId).isNotBlank().isNotEqualTo("client-made-id");
    }

    @Test
    @DisplayName("요청이 끝나면 요청 번호·IP·주체가 지워져 다음 요청에 남지 않는다")
    void clearsLogContextAfterRequest() throws Exception {
        String ownerToken = accounts.ownerToken(accounts.approvedOwner(accounts.newPhone()));

        mockMvc.perform(get(OWNER_LINK).header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk());

        assertThat(MDC.get(RequestIdFilter.REQUEST_ID_KEY)).isNull();
        assertThat(MDC.get(RequestIdFilter.CLIENT_IP_KEY)).isNull();
        assertThat(MDC.get(AuthInterceptor.ACTOR_LOG_KEY)).isNull();
    }
}