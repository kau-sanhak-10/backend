package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.infra.map.GeoPoint;
import com.ilro.backend.infra.map.MapClient.TravelEstimate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.BiFunction;

// 한 요청 안의 자동차 이동시간 - 같은 구간은 한 번만 물음 (후보마다 다시 묻지 않게, 실패도 기억)
// 요청을 넘는 기억·지도 오류 처리는 MapLookup (BE-10) - 요청 도중 그 기억이 만료돼도 이 요청 안에서는 같은 값
public final class TravelTimes {

    private final BiFunction<GeoPoint, GeoPoint, Optional<TravelEstimate>> source;
    private final Map<List<GeoPoint>, Optional<TravelEstimate>> asked = new HashMap<>();

    public TravelTimes(BiFunction<GeoPoint, GeoPoint, Optional<TravelEstimate>> source) {
        this.source = source;
    }

    public OptionalInt minutes(GeoPoint from, GeoPoint to) {
        return estimate(from, to).stream().mapToInt(TravelEstimate::minutes).findFirst();
    }

    public Optional<TravelEstimate> estimate(GeoPoint from, GeoPoint to) {
        return asked.computeIfAbsent(List.of(from, to), key -> source.apply(from, to));
    }

    // 이 요청에서 이미 물은 구간만 답하는 사본 - 업체를 잠근 뒤에는 지도를 부르지 않음 (명세 1.7, BE-11)
    // 잠그기 전 검사 때 없던 구간을 물으면(그사이 앞뒤 방문지가 바뀜) NotAskedException
    public TravelTimes askedOnly() {
        Map<List<GeoPoint>, Optional<TravelEstimate>> known = Map.copyOf(asked);
        return new TravelTimes((from, to) -> {
            Optional<TravelEstimate> estimate = known.get(List.of(from, to));
            if (estimate == null) {
                throw new NotAskedException();
            }
            return estimate;
        });
    }

    // 잠근 뒤 처음 보는 구간 - 부르는 쪽이 409 SLOT_UNAVAILABLE(TRAVEL_CONFLICT)로 바꿔 다시 고르게 함
    public static class NotAskedException extends RuntimeException {

        public NotAskedException() {
            super("잠그기 전에 확인하지 않은 이동 구간", null, false, false);
        }
    }
}
