package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.infra.map.GeoPoint;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

// 한 요청 안의 자동차 이동시간 - 같은 구간은 한 번만 물음 (후보마다 다시 묻지 않게, 실패도 기억)
// 요청을 넘는 기억·지도 오류 처리는 MapLookup (BE-10) - 요청 도중 그 기억이 만료돼도 이 요청 안에서는 같은 값
public final class TravelTimes {

    // 이동시간(분)을 묻는 곳 - 서비스는 MapLookup, 단위 테스트는 가짜 지도
    @FunctionalInterface
    public interface Source {

        OptionalInt minutes(GeoPoint from, GeoPoint to);
    }

    private final Source source;
    private final Map<List<GeoPoint>, OptionalInt> asked = new HashMap<>();

    public TravelTimes(Source source) {
        this.source = source;
    }

    public OptionalInt minutes(GeoPoint from, GeoPoint to) {
        return asked.computeIfAbsent(List.of(from, to), key -> source.minutes(from, to));
    }
}
