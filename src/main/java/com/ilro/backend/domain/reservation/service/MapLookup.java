package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.infra.map.GeoPoint;
import com.ilro.backend.infra.map.MapClient;
import com.ilro.backend.infra.map.ServiceAreaMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

// 지도 조회 (BE-10) - 이동시간·서비스 지역을 지도(MapClient)에 묻는 곳은 여기 한 곳
// 이동시간은 요청을 넘어 기억함 - 같은 두 지점(방향 구분)은 travel-cache-ttl 동안 지도에 다시 묻지 않음
//   지도가 답하지 못한 구간은 failure-cache-ttl만 기억 - 장애 때 같은 구간을 계속 묻지 않되 곧 다시 시도
//   travel-cache-size개를 넘으면 오래 안 쓴 구간부터 지움
// 지도 오류(예외)·형식에 안 맞는 값은 '모름'으로 - 0분이나 '지역 안'으로 바꾸지 않음 (명세 3.5)
//   모르면 고객 자동 예약은 사장님 확인으로 (TRAVEL_TIME_UNAVAILABLE·OUT_OF_SERVICE_AREA)
@Slf4j
@Component
public class MapLookup {

    private final MapClient mapClient;
    private final Clock clock;
    private final Duration travelTtl;
    private final Duration failureTtl;
    private final Map<Route, Cached> cache;

    private record Route(GeoPoint from, GeoPoint to) {
    }

    private record Cached(OptionalInt minutes, Instant expiresAt) {
    }

    public MapLookup(MapClient mapClient, Clock clock,
                     @Value("${app.map.travel-cache-ttl}") Duration travelTtl,
                     @Value("${app.map.failure-cache-ttl}") Duration failureTtl,
                     @Value("${app.map.travel-cache-size}") int maxEntries) {
        this.mapClient = mapClient;
        this.clock = clock;
        this.travelTtl = travelTtl;
        this.failureTtl = failureTtl;
        // 읽은 순서대로 - 가장 오래 안 쓴 구간이 맨 앞
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Route, Cached> eldest) {
                return size() > maxEntries;
            }
        };
    }

    // 한 요청(후보 계산·시간 검사 한 번) 동안 쓸 이동시간 - 그 요청 안에서 같은 구간은 같은 값
    public TravelTimes newRequest() {
        return new TravelTimes(this::travelMinutes);
    }

    // 자동차 이동시간(분) - 같은 곳이면 지도에 묻지 않고 0분, 모르면 빈 값
    public OptionalInt travelMinutes(GeoPoint from, GeoPoint to) {
        if (from.equals(to)) {
            return OptionalInt.of(0);
        }
        Route route = new Route(from, to);
        Instant now = clock.instant();
        synchronized (cache) {
            Cached cached = cache.get(route);
            if (cached != null && now.isBefore(cached.expiresAt())) {
                return cached.minutes();
            }
        }
        // 지도 호출은 잠금 밖에서 - 느린 지도가 다른 구간 조회를 막지 않게 (같은 구간이 동시에 오면 두 번 물을 수 있음)
        OptionalInt minutes = askTravel(from, to);
        synchronized (cache) {
            cache.put(route, new Cached(minutes, now.plus(minutes.isPresent() ? travelTtl : failureTtl)));
        }
        return minutes;
    }

    // 방문 좌표가 서비스 지역 안인지 - 지도 오류는 UNKNOWN (기억하지 않음, 요청마다 한 번)
    public ServiceAreaMatch serviceArea(GeoPoint point, List<String> regionCodes) {
        try {
            ServiceAreaMatch match = mapClient.matchServiceArea(point, regionCodes);
            return match == null ? ServiceAreaMatch.UNKNOWN : match;
        } catch (RuntimeException e) {
            log.warn("지도 서비스 지역 판별 실패 - 모름으로 처리: {}", e.getClass().getSimpleName());
            return ServiceAreaMatch.UNKNOWN;
        }
    }

    // 기억한 이동시간을 모두 지움 - 지도 응답을 바꿔 가며 확인하는 테스트용
    public void clear() {
        synchronized (cache) {
            cache.clear();
        }
    }

    // 로그에 좌표를 남기지 않음 (고객 방문 위치)
    private OptionalInt askTravel(GeoPoint from, GeoPoint to) {
        try {
            OptionalInt minutes = mapClient.carTravelMinutes(from, to);
            if (minutes == null || (minutes.isPresent() && minutes.getAsInt() < 0)) {
                log.warn("지도 이동시간 값이 약속(0 이상 분, 모르면 빈 값)과 달라 모름으로 처리: {}", minutes);
                return OptionalInt.empty();
            }
            return minutes;
        } catch (RuntimeException e) {
            log.warn("지도 이동시간 조회 실패 - 모름으로 처리: {}", e.getClass().getSimpleName());
            return OptionalInt.empty();
        }
    }
}
