package com.ilro.backend.infra.map;

import java.math.BigDecimal;
import java.util.Optional;

// 위도·경도 - 문의 방문 주소·업체 출발지·예약 주소의 좌표
// 같은 위치는 같은 값이 되게 끝자리 0을 지움 (DB numeric(10,7)은 37.6340000으로 읽힘)
public record GeoPoint(BigDecimal lat, BigDecimal lng) {

    public GeoPoint {
        lat = lat.stripTrailingZeros();
        lng = lng.stripTrailingZeros();
    }

    // 좌표가 하나라도 없으면 빈 값 - 주소만 있고 좌표가 없으면 지역·이동시간을 셀 수 없음
    public static Optional<GeoPoint> of(BigDecimal lat, BigDecimal lng) {
        return lat == null || lng == null ? Optional.empty() : Optional.of(new GeoPoint(lat, lng));
    }
}
