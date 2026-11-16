package com.ilro.backend.domain.address;

import com.ilro.backend.infra.map.AddressSearchClient;
import com.ilro.backend.infra.map.MapApiException;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 주소·지역 검색 (BE-15) - 로컬 프로필이라 가짜 검색(FakeAddressSearchClient)
// 지도 장애는 가짜 검색을 감싼 스파이로 그 테스트에서만 예외를 던지게 함
@SpringBootTest
class AddressSearchFlowTest {

    private static final String ALL_CONSENTS = """
            {"consents":[{"type":"PRIVACY_REQUIRED","termsVersion":"2026-10-v1"},
                         {"type":"PHOTO","termsVersion":"2026-10-v1"}]}""";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private AddressSearchClient addressSearchClient;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private long ownerId;
    private String ownerToken;

    private record Session(long inquiryId, String token) {

        String addresses(String query) {
            return "/api/v1/customer/inquiries/" + inquiryId + "/addresses?query=" + query;
        }
    }

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
    @DisplayName("고객 - 자기 문의 토큰이면 주소·좌표 목록, 맞는 게 없으면 200 + 빈 목록")
    void customerSearch() throws Exception {
        Session session = newInquiry();

        getAs(session.token(), session.addresses("항공대학로"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.addresses", hasSize(1)))
                .andExpect(jsonPath("$.result.addresses[0].address").value("경기 고양시 덕양구 항공대학로 76"))
                .andExpect(jsonPath("$.result.addresses[0].lat").value(37.6))
                .andExpect(jsonPath("$.result.addresses[0].lng").value(126.865));

        getAs(session.token(), session.addresses("강남"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.addresses", empty()));
    }

    @Test
    @DisplayName("고객 - 다른 문의 번호면 404, 토큰 없으면 401, 사장님 토큰으로는 고객 경로 403")
    void customerAccess() throws Exception {
        Session mine = newInquiry();
        Session other = newInquiry();

        getAs(mine.token(), other.addresses("항공대학로"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
        mockMvc.perform(get(mine.addresses("항공대학로")))
                .andExpect(status().isUnauthorized());
        getAs(ownerToken, mine.addresses("항공대학로"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("사장님 - 출발지 주소 검색, 서비스 지역 검색은 시군구 코드·시도 붙은 이름 (같은 '중구' 구분)")
    void ownerSearch() throws Exception {
        getAs(ownerToken, "/api/v1/owner/addresses?query=고양시청로")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.addresses[0].address").value("경기 고양시 덕양구 고양시청로 10"));

        getAs(ownerToken, "/api/v1/owner/regions?query=중구")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.regions", hasSize(2)))
                .andExpect(jsonPath("$.result.regions[0].regionCode").value("11140"))
                .andExpect(jsonPath("$.result.regions[0].regionName").value("서울 중구"))
                .andExpect(jsonPath("$.result.regions[1].regionName").value("부산 중구"));

        mockMvc.perform(get("/api/v1/owner/regions?query=중구"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("검색어가 비었거나 공백뿐이거나 100자 넘으면 400 INVALID_INPUT - 지도에 묻지 않음")
    void invalidQuery() throws Exception {
        for (String query : new String[]{"", "   ", "가".repeat(101)}) {
            mockMvc.perform(get("/api/v1/owner/addresses").param("query", query)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        getAs(ownerToken, "/api/v1/owner/regions")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("지도 장애면 503 MAP_UNAVAILABLE '잠시 후 다시' - 빈 목록으로 보이지 않게")
    void mapUnavailable() throws Exception {
        doThrow(new MapApiException("테스트 장애", null)).when(addressSearchClient).searchAddress(anyString(), anyInt());
        doThrow(new MapApiException("테스트 장애", null)).when(addressSearchClient).searchRegion(anyString(), anyInt());
        Session session = newInquiry();

        getAs(session.token(), session.addresses("항공대학로"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MAP_UNAVAILABLE"));
        getAs(ownerToken, "/api/v1/owner/regions?query=덕양")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MAP_UNAVAILABLE"));
    }

    // 공개 접수 링크로 새 상담 → 문의 번호·문의 토큰
    private Session newInquiry() throws Exception {
        String body = mockMvc.perform(post("/api/v1/public/intake/" + accounts.linkToken(ownerId) + "/inquiries")
                        .contentType(MediaType.APPLICATION_JSON).content(ALL_CONSENTS))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Session(((Number) JsonPath.read(body, "$.result.inquiryId")).longValue(),
                JsonPath.read(body, "$.result.inquiryToken"));
    }

    private ResultActions getAs(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }
}