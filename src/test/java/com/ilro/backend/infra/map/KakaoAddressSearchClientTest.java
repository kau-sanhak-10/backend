package com.ilro.backend.infra.map;

import com.ilro.backend.infra.map.AddressSearchClient.Address;
import com.ilro.backend.infra.map.AddressSearchClient.Region;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KakaoAddressSearchClientTest {

    private static final String SEARCH_URL = "https://dapi.kakao.com/v2/local/search/address.json";

    // 카카오 응답 모양 그대로 (쓰지 않는 칸 meta·zone_no 포함 - 무시되는지도 확인)
    private static final String DEOGYANG = """
            {"meta": {"total_count": 3},
             "documents": [
               {"address_name": "경기 고양시 덕양구", "address_type": "REGION", "x": "126.83", "y": "37.63",
                "address": {"address_name": "경기 고양시 덕양구", "region_1depth_name": "경기",
                            "region_2depth_name": "고양시 덕양구", "region_3depth_name": "", "b_code": "4128100000"},
                "road_address": null},
               {"address_name": "경기 고양시 덕양구 화전동 200-1", "address_type": "REGION_ADDR",
                "x": "126.865", "y": "37.6",
                "address": {"address_name": "경기 고양시 덕양구 화전동 200-1", "region_1depth_name": "경기",
                            "region_2depth_name": "고양시 덕양구", "region_3depth_name": "화전동", "b_code": "4128112100"},
                "road_address": {"address_name": "경기 고양시 덕양구 항공대학로 76", "zone_no": "10540"}},
               {"address_name": "경기", "address_type": "REGION", "x": "127.0", "y": "37.4",
                "address": {"address_name": "경기", "region_1depth_name": "경기",
                            "region_2depth_name": "", "region_3depth_name": "", "b_code": "4100000000"},
                "road_address": null}
             ]}
            """;

    private MockRestServiceServer server;
    private KakaoAddressSearchClient client;

    @BeforeEach
    void setUp() {
        // 진짜 카카오 대신 가짜 서버에 연결 (bindTo를 build보다 먼저)
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dapi.kakao.com");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KakaoAddressSearchClient(builder.build(), "test-key");
    }

    @Test
    @DisplayName("주소 검색 - KakaoAK 헤더·한글 검색어·개수, 정확한 주소만(지명 REGION 제외), x=경도·y=위도")
    void searchAddress() {
        server.expect(requestTo(startsWith(SEARCH_URL)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andExpect(queryParam("size", "10"))
                .andExpect(request -> assertThat(URLDecoder.decode(request.getURI().getRawQuery(),
                        StandardCharsets.UTF_8)).contains("query=덕양구 항공대학로"))
                .andRespond(withSuccess(DEOGYANG, MediaType.APPLICATION_JSON));

        assertThat(client.searchAddress("덕양구 항공대학로", 10)).containsExactly(
                new Address("경기 고양시 덕양구 항공대학로 76", "경기 고양시 덕양구 화전동 200-1",
                        new BigDecimal("37.6"), new BigDecimal("126.865")));
        server.verify();
    }

    @Test
    @DisplayName("지역 검색 - 법정동 코드 앞 5자리로 시군구 하나씩, 이름은 시도까지, 시도만 맞은 결과는 뺌")
    void searchRegion() {
        server.expect(requestTo(startsWith(SEARCH_URL)))
                .andExpect(queryParam("size", "30"))
                .andRespond(withSuccess(DEOGYANG, MediaType.APPLICATION_JSON));

        assertThat(client.searchRegion("덕양", 10)).containsExactly(new Region("41281", "경기 고양시 덕양구"));
        server.verify();
    }

    @Test
    @DisplayName("결과가 없으면 빈 목록 - 오류 아님")
    void noResult() {
        server.expect(requestTo(startsWith(SEARCH_URL)))
                .andRespond(withSuccess("{\"meta\":{\"total_count\":0},\"documents\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.searchAddress("없는주소", 10)).isEmpty();
    }

    @Test
    @DisplayName("카카오 오류 응답(5xx·429)·시간 초과는 MapApiException - 빈 목록으로 바꾸지 않음")
    void mapFailure() {
        server.expect(requestTo(startsWith(SEARCH_URL))).andRespond(withServerError());
        assertThatThrownBy(() -> client.searchAddress("덕양구", 10)).isInstanceOf(MapApiException.class);

        server.reset();
        server.expect(requestTo(startsWith(SEARCH_URL))).andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> client.searchRegion("덕양", 10)).isInstanceOf(MapApiException.class);

        server.reset();
        server.expect(requestTo(startsWith(SEARCH_URL))).andRespond(withException(new SocketTimeoutException()));
        assertThatThrownBy(() -> client.searchAddress("덕양구", 10)).isInstanceOf(MapApiException.class);
    }

    @Test
    @DisplayName("키가 비어 있으면 만들 때 바로 실패 - dev·prod 배포가 health에서 걸려 롤백되게")
    void blankKey() {
        assertThatThrownBy(() -> new KakaoAddressSearchClient(RestClient.create(), " "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("지역 검색 - 시군구 없는 세종은 시도 이름으로 남김 (코드 36110), 시도만 맞은 결과(XX000)는 뺌")
    void searchRegionSejong() {
        server.expect(requestTo(startsWith(SEARCH_URL))).andRespond(withSuccess("""
                {"documents": [
                  {"address_name": "세종특별자치시 대평동", "address_type": "REGION", "x": "127.29", "y": "36.47",
                   "address": {"address_name": "세종특별자치시 대평동", "region_1depth_name": "세종특별자치시",
                               "region_2depth_name": "", "region_3depth_name": "대평동", "b_code": "3611010400"}},
                  {"address_name": "세종특별자치시", "address_type": "REGION", "x": "127.28", "y": "36.48",
                   "address": {"address_name": "세종특별자치시", "region_1depth_name": "세종특별자치시",
                               "region_2depth_name": "", "region_3depth_name": "", "b_code": "3600000000"}}
                ]}""", MediaType.APPLICATION_JSON));

        assertThat(client.searchRegion("세종", 10)).containsExactly(new Region("36110", "세종특별자치시"));
    }
}