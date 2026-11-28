package com.ilro.backend.domain.reservation;

import com.ilro.backend.domain.reservation.exception.MapUnavailableException;
import com.ilro.backend.domain.reservation.service.MapLookup;
import com.ilro.backend.domain.reservation.service.TravelTimes;
import com.ilro.backend.infra.map.GeoPoint;
import com.ilro.backend.infra.map.MapClient;
import com.ilro.backend.infra.map.MapClient.TravelEstimate;
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
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 지도 조회 (BE-10) - 요청을 넘는 이동시간 기억(6시간, 셀 수 없음·장애는 1분), 셀 수 없다는 답은 '모름', 지도 오류·약속과 다른 값은 지도 장애
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
    private final Map<List<GeoPoint>, Supplier<Optional<TravelEstimate>>> answers = new HashMap<>();
    private final List<List<GeoPoint>> asked = new ArrayList<>();
    private ServiceAreaMatch area = ServiceAreaMatch.INSIDE;
    private RuntimeException areaError;

    private final MapClient map = new MapClient() {
        @Override
        public Optional<TravelEstimate> carTravel(GeoPoint from, GeoPoint to) {
            asked.add(List.of(from, to));
            return answers.getOrDefault(List.of(from, to), () -> Optional.of(new TravelEstimate(20, 2400))).get();
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
        assertThat(lookup.newDisplayRequest().estimate(A, B)).contains(new TravelEstimate(20, 2400));
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
    @DisplayName("지도가 셀 수 없다고 답하면 모름 - 0분으로 바꾸지 않고, 1분만 기억한 뒤 다시 물음")
    void cannotComputeIsUnknown() {
        MapLookup lookup = lookup(100);
        answers.put(List.of(A, B), Optional::empty);

        assertThat(lookup.travelMinutes(A, B)).isEmpty();
        now = now.plusSeconds(59);
        assertThat(lookup.travelMinutes(A, B)).isEmpty();
        assertThat(asked).as("1분 안에는 다시 묻지 않음").hasSize(1);

        answers.put(List.of(A, B), () -> Optional.of(new TravelEstimate(15, null)));
        now = now.plusSeconds(1);
        assertThat(lookup.travelMinutes(A, B)).hasValue(15);
        assertThat(asked).hasSize(2);
    }

    @Test
    @DisplayName("지도 장애(오류·시간 초과)·약속과 다른 값(음수·null)은 모름이 아니라 MapUnavailableException - 1분만 기억하고 지도가 돌아오면 바로 반영")
    void mapFailures() {
        MapLookup lookup = lookup(100);
        answers.put(List.of(A, B), () -> {
            throw new IllegalStateException("지도 응답 시간 초과");
        });
        answers.put(List.of(B, C), () -> Optional.of(new TravelEstimate(-5, null)));
        answers.put(List.of(C, A), () -> null);

        assertThatThrownBy(() -> lookup.travelMinutes(A, B)).isInstanceOf(MapUnavailableException.class);
        assertThatThrownBy(() -> lookup.travelMinutes(B, C)).isInstanceOf(MapUnavailableException.class);
        assertThatThrownBy(() -> lookup.travelMinutes(C, A)).isInstanceOf(MapUnavailableException.class);
        now = now.plusSeconds(59);
        assertThatThrownBy(() -> lookup.newRequest().minutes(A, B)).as("기억한 장애도 같음")
                .isInstanceOf(MapUnavailableException.class);
        assertThat(asked).as("1분 안에는 다시 묻지 않음").hasSize(3);

        answers.put(List.of(A, B), () -> Optional.of(new TravelEstimate(15, null)));
        now = now.plusSeconds(1);
        assertThat(lookup.travelMinutes(A, B)).as("지도가 돌아오면 바로 반영").hasValue(15);
        assertThat(asked).hasSize(4);
    }

    @Test
    @DisplayName("보여 주기용 요청(사장님 일정)은 지도 장애도 모름으로 - 예외 없이 빈 값, 셀 수 있는 구간은 그대로")
    void displayRequestShowsFailureAsUnknown() {
        MapLookup lookup = lookup(100);
        answers.put(List.of(A, B), () -> {
            throw new IllegalStateException("지도 서버 오류");
        });
        TravelTimes display = lookup.newDisplayRequest();

        assertThat(display.minutes(A, B)).isEmpty();
        assertThat(display.minutes(A, C)).hasValue(20);
        assertThatThrownBy(() -> lookup.newRequest().minutes(A, B)).as("예약 검사용 요청은 그대로 장애")
                .isInstanceOf(MapUnavailableException.class);
    }

    @Test
    @DisplayName("한 요청 안에서는 도중에 기억이 만료돼도 같은 구간을 다시 묻지 않음 (후보마다 같은 값)")
    void sameAnswerWithinRequest() {
        MapLookup lookup = lookup(100);
        TravelTimes request = lookup.newRequest();
        request.minutes(A, B);

        now = now.plus(Duration.ofHours(7));
        answers.put(List.of(A, B), () -> Optional.of(new TravelEstimate(40, 5100)));
        assertThat(request.estimate(A, B)).contains(new TravelEstimate(20, 2400));
        assertThat(asked).hasSize(1);
        assertThat(lookup.newRequest().estimate(A, B)).as("다음 요청은 새 값")
                .contains(new TravelEstimate(40, 5100));
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
    @DisplayName("서비스 지역 - 지도 답은 그대로(판별 불가는 UNKNOWN), 지도 오류·null은 MapUnavailableException (지역 안으로 보지 않음)")
    void serviceArea() {
        MapLookup lookup = lookup(100);
        assertThat(lookup.serviceArea(B, List.of("41281"))).isEqualTo(ServiceAreaMatch.INSIDE);

        area = ServiceAreaMatch.OUTSIDE;
        assertThat(lookup.serviceArea(B, List.of("41281"))).isEqualTo(ServiceAreaMatch.OUTSIDE);

        area = ServiceAreaMatch.UNKNOWN;
        assertThat(lookup.serviceArea(B, List.of("41281"))).isEqualTo(ServiceAreaMatch.UNKNOWN);

        area = null;
        assertThatThrownBy(() -> lookup.serviceArea(B, List.of("41281"))).isInstanceOf(MapUnavailableException.class);

        areaError = new IllegalStateException("지도 서버 오류");
        assertThatThrownBy(() -> lookup.serviceArea(B, List.of("41281"))).isInstanceOf(MapUnavailableException.class);

        areaError = null;
        area = ServiceAreaMatch.INSIDE;
        assertThat(lookup.serviceArea(B, List.of("41281"))).as("기억하지 않음 - 지도가 돌아오면 바로")
                .isEqualTo(ServiceAreaMatch.INSIDE);
    }

    private MapLookup lookup(int maxEntries) {
        return new MapLookup(map, clock, Duration.ofHours(6), Duration.ofMinutes(1), maxEntries);
    }

    private static GeoPoint point(String lat, String lng) {
        return new GeoPoint(new BigDecimal(lat), new BigDecimal(lng));
    }
}
