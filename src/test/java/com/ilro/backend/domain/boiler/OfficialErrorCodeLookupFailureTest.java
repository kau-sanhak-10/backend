package com.ilro.backend.domain.boiler;

import com.ilro.backend.domain.boiler.repository.OfficialManualModelRepository;
import com.ilro.backend.support.TestAccounts;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 공식 설명 조회 실패 (AI-14) - '공식 설명 미확인'으로 보여 주고, 상세 조회·사장님 수정은 그대로 됨
// (매뉴얼 조회가 따로 읽기 트랜잭션이라 실패해도 수정 트랜잭션이 롤백되지 않음)
@SpringBootTest
class OfficialErrorCodeLookupFailureTest {

    private static final String ALL_CONSENTS = """
            {"consents":[{"type":"PRIVACY_REQUIRED","termsVersion":"2026-10-v1"},
                         {"type":"PHOTO","termsVersion":"2026-10-v1"}]}""";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private OfficialManualModelRepository modelRepository;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private String ownerToken;
    private long ownerId;

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
    @DisplayName("매뉴얼 조회가 실패하면 LOOKUP_FAILED - 사장님 수정은 저장되고 상세도 열림")
    void lookupFailed() throws Exception {
        given(modelRepository.findByManufacturerAndModelKey(any(), any()))
                .willThrow(new QueryTimeoutException("매뉴얼 조회 시간 초과"));
        String body = mockMvc.perform(post("/api/v1/public/intake/" + accounts.linkToken(ownerId) + "/inquiries")
                        .contentType(MediaType.APPLICATION_JSON).content(ALL_CONSENTS))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long inquiryId = ((Number) JsonPath.read(body, "$.result.inquiryId")).longValue();
        String path = "/api/v1/owner/inquiries/" + inquiryId;
        int before = jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?", Integer.class, inquiryId);

        mockMvc.perform(patch(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + before + ",\"fields\":["
                                + "{\"fieldKey\":\"manufacturer\",\"value\":\"귀뚜라미\"},"
                                + "{\"fieldKey\":\"model\",\"value\":\"L20-22HFN\"},"
                                + "{\"fieldKey\":\"error_code\",\"value\":\"E001\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value(before + 1))
                .andExpect(jsonPath("$.result.officialErrorCode.status").value("UNCONFIRMED"))
                .andExpect(jsonPath("$.result.officialErrorCode.reason").value("LOOKUP_FAILED"));
        assertThat(jdbc.queryForMap("SELECT manufacturer, model, error_code, version FROM inquiry WHERE id = ?",
                inquiryId))
                .containsEntry("manufacturer", "귀뚜라미")
                .containsEntry("model", "L20-22HFN")
                .containsEntry("error_code", "E001")
                .containsEntry("version", before + 1);

        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.officialErrorCode.reason").value("LOOKUP_FAILED"));
    }
}
