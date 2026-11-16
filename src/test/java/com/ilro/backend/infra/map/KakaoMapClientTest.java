package com.ilro.backend.infra.map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KakaoMapClientTest {

    private static final String DIRECTIONS_URL = "https://apis-navi.kakaomobility.com/v1/directions";
    private static final String REGION_URL = "https://dapi.kakao.com/v2/local/geo/coord2regioncode.json";

    private static final GeoPoint HWAJEONG = new GeoPoint(new BigDecimal("37.634"), new BigDecimal("126.832"));
    private static final GeoPoint KAU = new GeoPoint(new BigDecimal("37.6"), new BigDecimal("126.865"));

    // 덕양구 화전동 - 법정동(B)·행정동(H) 둘 다 옴
    private static final String DEOGYANG_REGION = """
            {"documents": [
              {"region_type": "B", "code": "4128112100", "address_name": "경기도 고양시 덕양구 화전동"},
              {"region_type": "H", "code": "4128160000", "address_name": "경기도 고양시 덕양구 능곡동"}]}
            """;

    private MockRestServiceServer local;
    private MockRestServiceServer navi;
    private KakaoMapClient map;

    @BeforeEach
    void setUp() {
        RestClient.Builder localBuilder = RestClient.builder().baseUrl("https://dapi.kakao.com");
        RestClient.Builder naviBuilder = RestClient.builder().baseUrl("https://apis-navi.kakaomobility.com");
        local = MockRestServiceServer.bindTo(localBuilder).build();
        navi = MockRestServiceServer.bindTo(naviBuilder).build();
        map = new KakaoMapClient(localBuilder.build(), naviBuilder.build(), "test-key");
    }

    @Test
    @DisplayName("이동시간 - 경도,위도 순서·KakaoAK 헤더, 초는 분으로 올림 (1104초 → 19분)")
    void travelMinutes() {
        navi.expect(requestTo(startsWith(DIRECTIONS_URL)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andExpect(queryParam("origin", "126.832,37.634"))
                .andExpect(queryParam("destination", "126.865,37.6"))
                .andRespond(withSuccess("""
                        {"routes": [{"result_code": 0, "result_msg": "길찾기 성공",
                                     "summary": {"distance": 4210, "duration": 1104}}]}""",
                        MediaType.APPLICATION_JSON));

        assertThat(map.carTravelMinutes(HWAJEONG, KAU)).hasValue(19);
        navi.verify();
    }

    @Test
    @DisplayName("같은 좌표는 카카오에 묻지 않고 0분, 5m 이내(104)도 0분, 길을 못 찾으면 빈 값 (장애 아님)")
    void zeroAndNoRoute() {
        assertThat(map.carTravelMinutes(HWAJEONG,
                new GeoPoint(new BigDecimal("37.6340000"), new BigDecimal("126.8320000")))).hasValue(0);

        navi.expect(requestTo(startsWith(DIRECTIONS_URL))).andRespond(withSuccess(
                "{\"routes\":[{\"result_code\":104,\"result_msg\":\"출발지와 도착지가 5 m 이내\"}]}",
                MediaType.APPLICATION_JSON));
        assertThat(map.carTravelMinutes(HWAJEONG, KAU)).hasValue(0);

        navi.reset();
        navi.expect(requestTo(startsWith(DIRECTIONS_URL))).andRespond(withSuccess(
                "{\"routes\":[{\"result_code\":102,\"result_msg\":\"출발지 주변의 도로를 탐색할 수 없음\"}]}",
                MediaType.APPLICATION_JSON));
        assertThat(map.carTravelMinutes(HWAJEONG, KAU)).isEmpty();
    }

    @Test
    @DisplayName("길찾기 장애 - 오류 응답·시간 초과·빈 응답은 MapApiException (빈 값으로 바꾸지 않음)")
    void travelFailure() {
        navi.expect(requestTo(startsWith(DIRECTIONS_URL))).andRespond(withServerError());
        assertThatThrownBy(() -> map.carTravelMinutes(HWAJEONG, KAU)).isInstanceOf(MapApiException.class);

        navi.reset();
        navi.expect(requestTo(startsWith(DIRECTIONS_URL))).andRespond(withException(new SocketTimeoutException()));
        assertThatThrownBy(() -> map.carTravelMinutes(HWAJEONG, KAU)).isInstanceOf(MapApiException.class);

        navi.reset();
        navi.expect(requestTo(startsWith(DIRECTIONS_URL)))
                .andRespond(withSuccess("{\"routes\":[]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> map.carTravelMinutes(HWAJEONG, KAU)).isInstanceOf(MapApiException.class);
    }

    @Test
    @DisplayName("서비스 지역 - 법정동 코드가 저장한 시군구 코드로 시작하면 안, 아니면 밖 (행정동 코드는 안 씀)")
    void serviceArea() {
        local.expect(requestTo(startsWith(REGION_URL)))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andExpect(queryParam("x", "126.832"))
                .andExpect(queryParam("y", "37.634"))
                .andRespond(withSuccess(DEOGYANG_REGION, MediaType.APPLICATION_JSON));
        assertThat(map.matchServiceArea(HWAJEONG, List.of("11140", "41281"))).isEqualTo(ServiceAreaMatch.INSIDE);

        local.reset();
        local.expect(requestTo(startsWith(REGION_URL)))
                .andRespond(withSuccess(DEOGYANG_REGION, MediaType.APPLICATION_JSON));
        // 행정동 코드 앞자리(41281)와 다른 지역만 정한 업체
        assertThat(map.matchServiceArea(HWAJEONG, List.of("41285"))).isEqualTo(ServiceAreaMatch.OUTSIDE);
    }

    @Test
    @DisplayName("서비스 지역 - 지역을 안 정했으면 묻지 않고 밖, 행정구역이 없는 좌표는 UNKNOWN, 장애는 MapApiException")
    void serviceAreaEdges() {
        assertThat(map.matchServiceArea(HWAJEONG, List.of())).isEqualTo(ServiceAreaMatch.OUTSIDE);

        local.expect(requestTo(startsWith(REGION_URL)))
                .andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));
        assertThat(map.matchServiceArea(HWAJEONG, List.of("41281"))).isEqualTo(ServiceAreaMatch.UNKNOWN);

        local.reset();
        local.expect(requestTo(startsWith(REGION_URL))).andRespond(withServerError());
        assertThatThrownBy(() -> map.matchServiceArea(HWAJEONG, List.of("41281")))
                .isInstanceOf(MapApiException.class);
    }

    @Test
    @DisplayName("키가 비어 있으면 만들 때 바로 실패")
    void blankKey() {
        assertThatThrownBy(() -> new KakaoMapClient(RestClient.create(), RestClient.create(), ""))
                .isInstanceOf(IllegalStateException.class);
    }
}