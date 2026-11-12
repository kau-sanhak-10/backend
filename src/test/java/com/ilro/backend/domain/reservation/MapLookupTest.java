package com.ilro.backend.domain.reservation;

import com.ilro.backend.domain.reservation.service.MapLookup;
import com.ilro.backend.domain.reservation.service.TravelTimes;
import com.ilro.backend.infra.map.GeoPoint;
import com.ilro.backend.infra.map.MapClient;
import com.ilro.backend.infra.map.ServiceAreaMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

// 지도 조회 (BE-10) - 요청을 넘는 이동시간 기억(6시간, 실패는 1분), 지도 오류·약속과 다른 값은 '모름'
class MapLookupTest {

    private static final GeoPoint A = point("37.600", "126.800");
    private static final GeoPoint B = point("37.634", "126.832");
    private static final GeoPoint C = point("37.700", "126.900");

    private Instant now = Instant.parse("2099-01-01T00:00:00Z");
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };

    // 구간별 지도 응답 - 정하지 않은 구간은 20분
    private final Map<List<GeoPoint>, Supplier<OptionalInt>> answers = new HashMap<>();
    private final List<List<GeoPoint>> asked = new ArrayList<>();
    private ServiceAreaMatch area = ServiceAreaMatch.INSIDE;
    private RuntimeException areaError;

    private final MapClient map = new MapClient() {
        @Override
        public OptionalInt carTravelMinutes(GeoPoint from, GeoPoint to) {
            asked.add(List.of(from, to));
            return answers.getOrDefault(List.of(from, to), () -> OptionalInt.of(20)).get();
        }

        @Override
        public ServiceAreaMatch matchServiceArea(GeoPoint point, List<String> regionCodes) {
            if (areaError != null) {
                throw areaError;
            }
            return area;
        }
    };

    @Test
    @DisplayName("같은 구간은 요청을 넘어 지도에 한 번만 묻고, 방향이 다르면 따로 묻고, 같은 곳은 묻지 않고 0분")
    void remembersAcrossRequests() {
        MapLookup lookup = lookup(100);

        assertThat(lookup.newRequest().minutes(A, B)).hasValue(20);
        assertThat(lookup.newRequest().minutes(A, B)).hasValue(20);
        assertThat(lookup.travelMinutes(B, A)).hasValue(20);
        assertThat(lookup.travelMinutes(A, point("37.6000000", "126.8000000"))).as("끝자리 0만 다른 같은 곳").hasValue(0);
        assertThat(asked).containsExactly(List.of(A, B), List.of(B, A));
    }

    @Test
    @DisplayName("기억은 6시간 - 지나면 다시 물음")
    void expires() {
        MapLookup lookup = lookup(100);
        lookup.travelMinutes(A, B);

        now = now.plus(Duration.ofHours(6)).minusSeconds(1);
        lookup.travelMinutes(A, B);
        assertThat(asked).hasSize(1);

        now = now.plusSeconds(1);
        lookup.travelMinutes(A, B);
        assertThat(asked).hasSize(2);
    }

    @Test
    @DisplayName("지도가 답하지 못함·오류·음수·null은 모름 - 0분으로 바꾸지 않고, 1분만 기억한 뒤 다시 물음")
    void failuresAreUnknown() {
        MapLookup lookup = lookup(100);
        answers.put(List.of(A, B), OptionalInt::empty);
        answers.put(List.of(A, C), () -> {
            throw new IllegalStateException("지도 응답 시간 초과");
        });
        answers.put(List.of(B, C), () -> OptionalInt.of(-5));
        answers.put(List.of(C, A), () -> null);

        assertThat(lookup.travelMinutes(A, B)).isEmpty();
        assertThat(lookup.travelMinutes(A, C)).isEmpty();
        assertThat(lookup.travelMinutes(B, C)).isEmpty();
        assertThat(lookup.travelMinutes(C, A)).isEmpty();
        now = now.plusSeconds(59);
        assertThat(lookup.travelMinutes(A, B)).isEmpty();
        assertThat(asked).as("1분 안에는 다시 묻지 않음").hasSize(4);

        answers.put(List.of(A, B), () -> OptionalInt.of(15));
        now = now.plusSeconds(1);
        assertThat(lookup.travelMinutes(A, B)).as("지도가 돌아오면 바로 반영").hasValue(15);
        assertThat(asked).hasSize(5);
    }

    @Test
    @DisplayName("한 요청 안에서는 도중에 기억이 만료돼도 같은 구간을 다시 묻지 않음 (후보마다 같은 값)")
    void sameAnswerWithinRequest() {
        MapLookup lookup = lookup(100);
        TravelTimes request = lookup.newRequest();
        request.minutes(A, B);

        now = now.plus(Duration.ofHours(7));
        answers.put(List.of(A, B), () -> OptionalInt.of(40));
        assertThat(request.minutes(A, B)).hasValue(20);
        assertThat(asked).hasSize(1);
        assertThat(lookup.newRequest().minutes(A, B)).as("다음 요청은 새 값").hasValue(40);
    }

    @Test
    @DisplayName("기억하는 구간 수를 넘으면 오래 안 쓴 구간부터 지움")
    void evictsLeastRecentlyUsed() {
        MapLookup lookup = lookup(2);
        lookup.travelMinutes(A, B);
        lookup.travelMinutes(A, C);
        lookup.travelMinutes(A, B);
        lookup.travelMinutes(B, C);
        lookup.travelMinutes(A, B);
        lookup.travelMinutes(A, C);

        assertThat(asked).as("A→C가 가장 오래 안 쓴 구간이라 지워짐")
                .containsExactly(List.of(A, B), List.of(A, C), List.of(B, C), List.of(A, C));
    }

    @Test
    @DisplayName("서비스 지역 - 지도 답은 그대로, 지도 오류·null은 UNKNOWN (지역 안으로 보지 않음)")
    void serviceArea() {
        MapLookup lookup = lookup(100);
        assertThat(lookup.serviceArea(B, List.of("41281"))).isEqualTo(ServiceAreaMatch.INSIDE);

        area = ServiceAreaMatch.OUTSIDE;
        assertThat(lookup.serviceArea(B, List.of("41281"))).isEqualTo(ServiceAreaMatch.OUTSIDE);

        area = null;
        assertThat(lookup.serviceArea(B, List.of("41281"))).isEqualTo(ServiceAreaMatch.UNKNOWN);

        areaError = new IllegalStateException("지도 서버 오류");
        assertThat(lookup.serviceArea(B, List.of("41281"))).isEqualTo(ServiceAreaMatch.UNKNOWN);
    }

    private MapLookup lookup(int maxEntries) {
        return new MapLookup(map, clock, Duration.ofHours(6), Duration.ofMinutes(1), maxEntries);
    }

    private static GeoPoint point(String lat, String lng) {
        return new GeoPoint(new BigDecimal(lat), new BigDecimal(lng));
    }
}
