package com.ilro.backend.infra.map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

// 지도 약속 (MapClient 주석) - 지도 클라이언트마다 이 클래스를 상속한 테스트를 둠
// BE-15 실제 지도도 같은 확인을 통과해야 함 (실제 지도 연동 확인 - 테스트 응답 확인과 따로, 키가 있을 때만 돌림)
public abstract class MapClientContract {

    // 고양시 덕양구 화정동 근처 두 곳 (약 1km)
    protected static final GeoPoint HWAJEONG = new GeoPoint(new BigDecimal("37.634"), new BigDecimal("126.832"));
    protected static final GeoPoint NEARBY = new GeoPoint(new BigDecimal("37.642"), new BigDecimal("126.838"));

    protected abstract MapClient map();

    @Test
    @DisplayName("같은 좌표는 0분 (끝자리 0만 다른 값도 같은 곳)")
    void samePointIsZero() {
        GeoPoint same = new GeoPoint(new BigDecimal("37.6340000"), new BigDecimal("126.8320000"));
        assertThat(map().carTravelMinutes(HWAJEONG, same)).hasValue(0);
    }

    @Test
    @DisplayName("가까운 두 곳은 0보다 크고 2시간 안의 정수 분, 거꾸로 가는 길도 답함")
    void nearbyMinutes() {
        for (OptionalInt minutes : List.of(map().carTravelMinutes(HWAJEONG, NEARBY),
                map().carTravelMinutes(NEARBY, HWAJEONG))) {
            assertThat(minutes).isPresent();
            assertThat(minutes.getAsInt()).isPositive().isLessThanOrEqualTo(120);
        }
    }

    @Test
    @DisplayName("서비스 지역을 안 정했으면 지역 안이 아님 (UNKNOWN도 '지역 안'으로 바꾸지 않음)")
    void noRegionIsNotInside() {
        assertThat(map().matchServiceArea(HWAJEONG, List.of())).isNotEqualTo(ServiceAreaMatch.INSIDE);
    }
}
