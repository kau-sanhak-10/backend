package com.ilro.backend.infra.map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.OptionalInt;

// 가짜 지도 - 로컬·개발 서버에서 방문 가능 시간 화면을 만들 수 있게 (실제 도로·지역 아님)
// 이동시간: 두 좌표의 직선거리 × 1.3을 시속 30km로 달린 시간, 5분 단위로 올림 (같은 곳이면 0분)
// 지역: 업체가 서비스 지역을 하나라도 정했으면 모두 지역 안으로 봄
// 운영(prod)에서는 만들지 않음 - BE-15 실제 지도 연동 전까지는 UnavailableMapClient
@Component
@Profile("!prod")
public class FakeMapClient implements MapClient {

    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final double ROAD_FACTOR = 1.3;
    private static final double SPEED_KMH = 30.0;

    @Override
    public OptionalInt carTravelMinutes(GeoPoint from, GeoPoint to) {
        if (from.equals(to)) {
            return OptionalInt.of(0);
        }
        double minutes = distanceKm(from, to) * ROAD_FACTOR / SPEED_KMH * 60;
        return OptionalInt.of(Math.max(5, (int) Math.ceil(minutes / 5) * 5));
    }

    @Override
    public ServiceAreaMatch matchServiceArea(GeoPoint point, List<String> regionCodes) {
        return regionCodes.isEmpty() ? ServiceAreaMatch.OUTSIDE : ServiceAreaMatch.INSIDE;
    }

    private static double distanceKm(GeoPoint from, GeoPoint to) {
        double lat1 = Math.toRadians(from.lat().doubleValue());
        double lat2 = Math.toRadians(to.lat().doubleValue());
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(to.lng().doubleValue() - from.lng().doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }
}
