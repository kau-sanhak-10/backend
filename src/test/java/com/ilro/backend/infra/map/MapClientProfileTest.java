package com.ilro.backend.infra.map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 지도 클라이언트 프로필 - dev·prod는 카카오(KakaoMapClient), local·테스트는 가짜 지도
// 운영에서 가짜 지도가 실제 고객 예약 시간을 만들지 않게 확인
class MapClientProfileTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FakeMapClient.class, KakaoMapClient.class)
            .withPropertyValues("app.kakao.local-base-url=https://dapi.kakao.com",
                    "app.kakao.navi-base-url=https://apis-navi.kakaomobility.com");

    @Test
    @DisplayName("dev·prod는 카카오 지도 하나, 그 밖의 프로필은 가짜 지도 하나 - 운영에서 가짜 지도가 예약 시간을 만들지 않게")
    void clientByProfile() {
        for (String profile : List.of("dev", "prod")) {
            runner.withPropertyValues("spring.profiles.active=" + profile, "app.kakao.rest-api-key=test-key")
                    .run(context -> assertThat(context).hasSingleBean(MapClient.class)
                            .hasSingleBean(KakaoMapClient.class));
        }
        runner.withPropertyValues("spring.profiles.active=local")
                .run(context -> assertThat(context).hasSingleBean(MapClient.class)
                        .hasSingleBean(FakeMapClient.class));
    }

    @Test
    @DisplayName("dev·prod에서 카카오 키가 비어 있으면 서버가 뜨지 않음 - 배포 health에서 걸려 롤백")
    void blankKeyFailsOnServer() {
        runner.withPropertyValues("spring.profiles.active=prod", "app.kakao.rest-api-key=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("가짜 지도 - 같은 곳 0분, 그 밖엔 최소 5분·5분 단위, 서비스 지역이 없으면 지역 밖")
    void fakeMap() {
        FakeMapClient map = new FakeMapClient();
        GeoPoint a = new GeoPoint(new BigDecimal("37.6340000"), new BigDecimal("126.8320000"));
        GeoPoint same = new GeoPoint(new BigDecimal("37.634"), new BigDecimal("126.832"));
        GeoPoint near = new GeoPoint(new BigDecimal("37.635"), new BigDecimal("126.832"));
        GeoPoint far = new GeoPoint(new BigDecimal("37.700"), new BigDecimal("126.900"));

        assertThat(map.carTravelMinutes(a, same)).hasValue(0);
        assertThat(map.carTravelMinutes(a, near)).hasValue(5);
        assertThat(map.carTravelMinutes(a, far).getAsInt()).isGreaterThan(5).matches(minutes -> minutes % 5 == 0);
        assertThat(map.matchServiceArea(a, List.of())).isEqualTo(ServiceAreaMatch.OUTSIDE);
        assertThat(map.matchServiceArea(a, List.of("41281"))).isEqualTo(ServiceAreaMatch.INSIDE);
    }
}
