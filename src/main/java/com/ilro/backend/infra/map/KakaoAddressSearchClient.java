package com.ilro.backend.infra.map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

// 카카오 로컬 API로 주소·지역 검색 (dev·prod) - local·테스트는 FakeAddressSearchClient
// 인증 헤더: Authorization: KakaoAK {REST API 키}
// 장애(타임아웃·연결 실패·오류 응답)는 MapApiException, 결과 없음은 빈 목록
@Slf4j
@Component
@Profile({"dev", "prod"})
public class KakaoAddressSearchClient implements AddressSearchClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    // 카카오 주소 검색은 한 번에 최대 30개
    private static final int KAKAO_MAX_SIZE = 30;

    private final RestClient restClient;
    private final String authorization;

    @Autowired
    public KakaoAddressSearchClient(@Value("${app.kakao.local-base-url}") String baseUrl,
                                    @Value("${app.kakao.rest-api-key}") String restApiKey) {
        this(RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory()).build(), restApiKey);
    }

    // 실제 초기화(키 검사 포함) - 위 생성자가 이걸 부르고, 테스트는 가짜 서버 RestClient로 직접 부름
    KakaoAddressSearchClient(RestClient restClient, String restApiKey) {
        // dev·prod에서 키가 비어 있으면 서버가 아예 안 뜨게 → 배포 health 실패 → 자동 롤백
        if (restApiKey == null || restApiKey.isBlank()) {
            throw new IllegalStateException("KAKAO_REST_API_KEY 설정이 필요합니다");
        }
        this.restClient = restClient;
        this.authorization = "KakaoAK " + restApiKey;
    }

    // 정확한 주소만 - 지명(REGION)·도로 이름만(ROAD)은 방문 주소가 아니라 뺌
    private static final Set<String> EXACT_ADDRESS_TYPES = Set.of("REGION_ADDR", "ROAD_ADDR");

    @Override
    public List<Address> searchAddress(String query, int size) {
        return search(query, size).stream()
                .filter(doc -> EXACT_ADDRESS_TYPES.contains(doc.addressType()))
                .filter(doc -> doc.x() != null && doc.y() != null)
                .map(doc -> new Address(
                        // 도로명 주소가 있으면 그걸, 없으면 지번 주소를 대표 주소로
                        doc.roadAddress() != null ? doc.roadAddress().addressName() : doc.addressName(),
                        doc.address() != null ? doc.address().addressName() : null,
                        // 카카오는 x = 경도(lng), y = 위도(lat), 문자열로 줌
                        new BigDecimal(doc.y()), new BigDecimal(doc.x())))
                .toList();
    }

    // 시군구 단위로 모음 - "덕양구"는 물론 "화전동"·도로명으로 찾아도 그 주소의 시군구가 나옴
    // regionCode: 법정동 코드 앞 5자리, regionName: "경기 고양시 덕양구" (다른 시도의 같은 이름 "중구" 구분)
    @Override
    public List<Region> searchRegion(String query, int size) {
        Map<String, Region> regions = new LinkedHashMap<>();
        for (Document doc : search(query, KAKAO_MAX_SIZE)) {
            KakaoAddress address = doc.address();
            if (address == null || address.bCode() == null || address.bCode().length() < 5) {
                continue;
            }
            String code = address.bCode().substring(0, 5);
            boolean hasDistrict = address.region2() != null && !address.region2().isBlank();
            // 시도만 맞은 결과("경기" 41000)는 시군구가 없어서 뺌
            // 세종처럼 시군구 없이 시도 아래가 바로 동·읍면인 곳은 코드(36110)가 시군구 자리를 가져서 시도 이름으로 남김
            if (!hasDistrict && code.endsWith("000")) {
                continue;
            }
            String name = hasDistrict ? address.region1() + " " + address.region2() : address.region1();
            regions.putIfAbsent(code, new Region(code, name));
        }
        return regions.values().stream().limit(size).toList();
    }

    // 카카오 주소 검색 한 번 - 결과 없음은 빈 목록, 장애는 MapApiException
    // 검색어(고객 주소)는 로그에 남기지 않음
    private List<Document> search(String query, int size) {
        try {
            SearchResponse response = restClient.get()
                    .uri(uri -> uri.path("/v2/local/search/address.json")
                            .queryParam("query", query)
                            .queryParam("size", Math.min(size, KAKAO_MAX_SIZE))
                            .build())
                    .header("Authorization", authorization)
                    .retrieve()
                    .body(SearchResponse.class);
            return response == null || response.documents() == null ? List.of() : response.documents();
        } catch (RestClientResponseException e) {
            // 카카오가 오류로 응답 (키 문제 401·한도 초과 429·서버 오류 5xx 등)
            log.warn("카카오 주소 검색 오류 응답: status={}", e.getStatusCode().value());
            throw new MapApiException("카카오 주소 검색 오류 응답", e);
        } catch (RestClientException e) {
            // 타임아웃·연결 실패·응답 형식 오류
            log.warn("카카오 주소 검색 실패: {}", e.getClass().getSimpleName());
            throw new MapApiException("카카오 주소 검색 실패", e);
        }
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    // 카카오 응답 - 쓰는 칸만 받고 나머지는 무시
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(List<Document> documents) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Document(@JsonProperty("address_name") String addressName,
                    @JsonProperty("address_type") String addressType,
                    String x, String y,
                    KakaoAddress address,
                    @JsonProperty("road_address") KakaoRoadAddress roadAddress) {
    }

    // 지번 주소 - b_code: 법정동 코드 10자리 (앞 5자리 = 시군구)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record KakaoAddress(@JsonProperty("address_name") String addressName,
                        @JsonProperty("region_1depth_name") String region1,
                        @JsonProperty("region_2depth_name") String region2,
                        @JsonProperty("region_3depth_name") String region3,
                        @JsonProperty("b_code") String bCode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record KakaoRoadAddress(@JsonProperty("address_name") String addressName) {
    }
}