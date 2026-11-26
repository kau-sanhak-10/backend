package com.ilro.backend.infra.map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

// 카카오 지도 (dev·prod, BE-15) - 이동시간은 카카오모빌리티 자동차 길찾기, 서비스 지역은 좌표 → 법정동 코드
// MapClient 약속: 계산할 수 없다는 답(경로 없음·행정구역 밖)은 빈 값·UNKNOWN,
//   지도 장애(시간 초과·연결 실패·오류 응답·빈 응답)는 MapApiException → MapLookup이 503 MAP_UNAVAILABLE
// 길찾기는 '지금 출발' 기준 - 출발 시각마다 달라지는 미래 길찾기는 같은 구간 기억(MapLookup)과 맞지 않아 쓰지 않음
// 좌표는 로그에 남기지 않음 (고객 방문 위치)
@Slf4j
@Component
@Profile({"dev", "prod"})
public class KakaoMapClient implements MapClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    // 길찾기 결과 코드 - 0 성공, 104 출발지·도착지가 5m 이내 (같은 곳으로 봄), 그 밖은 경로를 찾지 못함
    private static final int ROUTE_OK = 0;
    private static final int ROUTE_TOO_CLOSE = 104;
    // 행정구역 종류 - B 법정동 (H 행정동은 코드 체계가 달라 쓰지 않음)
    private static final String LEGAL_REGION = "B";

    private final RestClient localClient;
    private final RestClient naviClient;
    private final String authorization;

    @Autowired
    public KakaoMapClient(@Value("${app.kakao.local-base-url}") String localBaseUrl,
                          @Value("${app.kakao.navi-base-url}") String naviBaseUrl,
                          @Value("${app.kakao.rest-api-key}") String restApiKey) {
        this(RestClient.builder().baseUrl(localBaseUrl).requestFactory(requestFactory()).build(),
                RestClient.builder().baseUrl(naviBaseUrl).requestFactory(requestFactory()).build(),
                restApiKey);
    }

    // 실제 초기화(키 검사 포함) - 위 생성자가 이걸 부르고, 테스트는 가짜 서버 RestClient로 직접 부름
    KakaoMapClient(RestClient localClient, RestClient naviClient, String restApiKey) {
        if (restApiKey == null || restApiKey.isBlank()) {
            throw new IllegalStateException("KAKAO_REST_API_KEY 설정이 필요합니다");
        }
        this.localClient = localClient;
        this.naviClient = naviClient;
        this.authorization = "KakaoAK " + restApiKey;
    }

    @Override
    public Optional<TravelEstimate> carTravel(GeoPoint from, GeoPoint to) {
        if (from.equals(to)) {
            return Optional.of(new TravelEstimate(0, 0));
        }
        DirectionsResponse response = call("카카오 길찾기", () -> naviClient.get()
                .uri(uri -> uri.path("/v1/directions")
                        .queryParam("origin", lngLat(from))
                        .queryParam("destination", lngLat(to))
                        .queryParam("summary", true)
                        .build())
                .header("Authorization", authorization)
                .retrieve()
                .body(DirectionsResponse.class));
        if (response == null || response.routes() == null || response.routes().isEmpty()) {
            throw new MapApiException("카카오 길찾기 응답이 비어 있음", null);
        }
        Route route = response.routes().get(0);
        if (route.resultCode() == ROUTE_TOO_CLOSE) {
            return Optional.of(new TravelEstimate(0, null));
        }
        if (route.resultCode() != ROUTE_OK || route.summary() == null) {
            // 길이 없음·주변에 도로 없음 등 - 지도가 '셀 수 없다'고 답함 (장애 아님)
            log.info("카카오 길찾기 경로 없음: resultCode={}", route.resultCode());
            return Optional.empty();
        }
        // 초 → 분, 올림 (61초는 2분)
        Summary summary = route.summary();
        Integer distance = summary.distance();
        return Optional.of(new TravelEstimate((summary.duration() + 59) / 60,
                distance != null && distance >= 0 ? distance : null));
    }

    @Override
    public ServiceAreaMatch matchServiceArea(GeoPoint point, List<String> regionCodes) {
        if (regionCodes.isEmpty()) {
            return ServiceAreaMatch.OUTSIDE;
        }
        RegionCodeResponse response = call("카카오 좌표 → 행정구역", () -> localClient.get()
                .uri(uri -> uri.path("/v2/local/geo/coord2regioncode.json")
                        .queryParam("x", point.lng().toPlainString())
                        .queryParam("y", point.lat().toPlainString())
                        .build())
                .header("Authorization", authorization)
                .retrieve()
                .body(RegionCodeResponse.class));
        if (response == null || response.documents() == null) {
            throw new MapApiException("카카오 행정구역 응답이 비어 있음", null);
        }
        Optional<String> legalCode = response.documents().stream()
                .filter(doc -> LEGAL_REGION.equals(doc.regionType()) && doc.code() != null)
                .map(RegionDocument::code)
                .findFirst();
        if (legalCode.isEmpty()) {
            // 바다·국외 등 행정구역이 없는 좌표 - 판별할 수 없음
            return ServiceAreaMatch.UNKNOWN;
        }
        // 저장된 지역 코드(시군구 5자리, 더 좁은 코드도 가능)로 시작하면 그 지역 안
        return regionCodes.stream().anyMatch(legalCode.get()::startsWith)
                ? ServiceAreaMatch.INSIDE : ServiceAreaMatch.OUTSIDE;
    }

    // 카카오 호출 한 번 - 오류 응답·시간 초과·연결 실패는 MapApiException
    private static <T> T call(String what, Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            log.warn("{} 오류 응답: status={}", what, e.getStatusCode().value());
            throw new MapApiException(what + " 오류 응답", e);
        } catch (RestClientException e) {
            log.warn("{} 실패: {}", what, e.getClass().getSimpleName());
            throw new MapApiException(what + " 실패", e);
        }
    }

    // 카카오모빌리티 좌표 형식 "경도,위도"
    private static String lngLat(GeoPoint point) {
        return point.lng().toPlainString() + "," + point.lat().toPlainString();
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    // 카카오 응답 - 쓰는 칸만 받고 나머지는 무시
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DirectionsResponse(List<Route> routes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Route(@JsonProperty("result_code") int resultCode, Summary summary) {
    }

    // duration: 초, distance: 미터
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Summary(int duration, Integer distance) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegionCodeResponse(List<RegionDocument> documents) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegionDocument(@JsonProperty("region_type") String regionType, String code) {
    }
}
