package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.infra.map.GeoPoint;
import com.ilro.backend.infra.map.MapClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

// 자동차 이동시간 - 한 요청 안에서 같은 구간은 지도에 다시 묻지 않음 (요청을 넘는 캐시는 BE-10)
// 실패도 기억함 - 같은 구간을 후보마다 다시 물어 지도 호출 한도를 쓰지 않게
public final class TravelTimes {

    private final MapClient mapClient;
    private final Map<List<GeoPoint>, OptionalInt> cache = new HashMap<>();

    public TravelTimes(MapClient mapClient) {
        this.mapClient = mapClient;
    }

    public OptionalInt minutes(GeoPoint from, GeoPoint to) {
        return cache.computeIfAbsent(List.of(from, to), key -> mapClient.carTravelMinutes(from, to));
    }
}
