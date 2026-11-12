package com.ilro.backend.infra.map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 운영(prod)에서 가짜 지도가 실제 고객 예약 시간을 만들지 않게 - BE-15 연동 전까지 운영은 '모름'만 답함
class MapClientProfileTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FakeMapClient.class, UnavailableMapClient.class);

    @Test
    @DisplayName("prod는 UnavailableMapClient 하나 (이동시간 없음·지역 UNKNOWN), 그 밖의 프로필은 가짜 지도 하나")
    void clientByProfile() {
        GeoPoint point = new GeoPoint(new BigDecimal("37.634"), new BigDecimal("126.832"));
        runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            assertThat(context).hasSingleBean(MapClient.class).hasSingleBean(UnavailableMapClient.class);
            MapClient map = context.getBean(MapClient.class);
            assertThat(map.carTravelMinutes(point, point)).isEmpty();
            assertThat(map.matchServiceArea(point, List.of("41281"))).isEqualTo(ServiceAreaMatch.UNKNOWN);
        });
        for (String profile : List.of("local", "dev")) {
            runner.withPropertyValues("spring.profiles.active=" + profile)
                    .run(context -> assertThat(context).hasSingleBean(MapClient.class)
                            .hasSingleBean(FakeMapClient.class));
        }
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
