package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.reservation.exception.MapUnavailableException;
import com.ilro.backend.infra.map.GeoPoint;
import com.ilro.backend.infra.map.MapClient;
import com.ilro.backend.infra.map.MapClient.TravelEstimate;
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
import java.util.Optional;
import java.util.OptionalInt;

// 지도 조회 (BE-10) - 이동시간·서비스 지역을 지도(MapClient)에 묻는 곳은 여기 한 곳
// 지도의 답은 두 가지로 나눔
//   셀 수 없다는 답(빈 값·UNKNOWN): '모름' - 0분이나 '지역 안'으로 바꾸지 않음 (명세 3.5)
//     고객 자동 예약은 사장님 확인으로 (422 TRAVEL_TIME_UNAVAILABLE·OUT_OF_SERVICE_AREA)
//   지도 장애(예외·시간 초과·약속과 다른 값): MapUnavailableException
//     고객에게는 503 MAP_UNAVAILABLE "잠시 후 다시", 문의는 그대로 둠 - 잠깐 장애로 문의가 사장님 확인으로 넘어가지 않게
// 이동시간은 요청을 넘어 기억함 - 같은 두 지점(방향 구분)은 travel-cache-ttl 동안 지도에 다시 묻지 않음
//   셀 수 없다는 답·지도 장애는 failure-cache-ttl만 기억 - 장애 때 같은 구간을 계속 묻지 않되 곧 다시 시도
//   travel-cache-size개를 넘으면 오래 안 쓴 구간부터 지움
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

    // mapFailed면 estimate는 쓰지 않음
    private record Cached(Optional<TravelEstimate> estimate, boolean mapFailed, Instant expiresAt) {

        Optional<TravelEstimate> answer() {
            if (mapFailed) {
                throw new MapUnavailableException();
            }
            return estimate;
        }
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
    // 지도 장애면 MapUnavailableException (고객 후보·예약 시간 검사는 503으로 끝남)
    public TravelTimes newRequest() {
        return new TravelTimes(this::travel);
    }

    // 보여 주기만 하는 요청(사장님 일정 B-28)용 - 지도 장애도 '모름'으로 보여 줌
    // 결과를 DB에 남기지 않고 다음 조회 때 다시 셈 - 장애 동안 일정 화면 전체가 막히지 않게
    public TravelTimes newDisplayRequest() {
        return new TravelTimes((from, to) -> {
            try {
                return travel(from, to);
            } catch (MapUnavailableException e) {
                return Optional.empty();
            }
        });
    }

    // 자동차 이동시간(분) - 같은 곳이면 지도에 묻지 않고 0분, 지도가 셀 수 없다고 하면 빈 값
    // 지도 장애면 MapUnavailableException (기억한 장애도 같음)
    public OptionalInt travelMinutes(GeoPoint from, GeoPoint to) {
        return travel(from, to).stream().mapToInt(TravelEstimate::minutes).findFirst();
    }

    private Optional<TravelEstimate> travel(GeoPoint from, GeoPoint to) {
        if (from.equals(to)) {
            return Optional.of(new TravelEstimate(0, 0));
        }
        Route route = new Route(from, to);
        Instant now = clock.instant();
        synchronized (cache) {
            Cached cached = cache.get(route);
            if (cached != null && now.isBefore(cached.expiresAt())) {
                return cached.answer();
            }
        }
        // 지도 호출은 잠금 밖에서 - 느린 지도가 다른 구간 조회를 막지 않게 (같은 구간이 동시에 오면 두 번 물을 수 있음)
        Cached answer = askTravel(from, to, now);
        synchronized (cache) {
            cache.put(route, answer);
        }
        return answer.answer();
    }

    // 방문 좌표가 서비스 지역 안인지 - 지도 답 그대로 (판별할 수 없다고 하면 UNKNOWN)
    // 지도 장애(예외·null)는 MapUnavailableException (기억하지 않음, 요청마다 한 번)
    public ServiceAreaMatch serviceArea(GeoPoint point, List<String> regionCodes) {
        ServiceAreaMatch match;
        try {
            match = mapClient.matchServiceArea(point, regionCodes);
        } catch (RuntimeException e) {
            log.warn("지도 서비스 지역 판별 실패 - 지도 장애로 처리: {}", e.getClass().getSimpleName());
            throw new MapUnavailableException();
        }
        if (match == null) {
            log.warn("지도 서비스 지역 값이 없음 - 지도 장애로 처리");
            throw new MapUnavailableException();
        }
        return match;
    }

    // 기억한 이동시간을 모두 지움 - 지도 응답을 바꿔 가며 확인하는 테스트용
    public void clear() {
        synchronized (cache) {
            cache.clear();
        }
    }

    // 로그에 좌표를 남기지 않음 (고객 방문 위치)
    private Cached askTravel(GeoPoint from, GeoPoint to, Instant now) {
        try {
            Optional<TravelEstimate> estimate = mapClient.carTravel(from, to);
            if (estimate == null || (estimate.isPresent() && estimate.get().minutes() < 0)) {
                log.warn("지도 이동시간 값이 약속(0 이상 분, 모르면 빈 값)과 달라 지도 장애로 처리: {}", estimate);
                return failed(now);
            }
            return new Cached(estimate, false, now.plus(estimate.isPresent() ? travelTtl : failureTtl));
        } catch (RuntimeException e) {
            log.warn("지도 이동시간 조회 실패 - 지도 장애로 처리: {}", e.getClass().getSimpleName());
            return failed(now);
        }
    }

    private Cached failed(Instant now) {
        return new Cached(Optional.empty(), true, now.plus(failureTtl));
    }
}
