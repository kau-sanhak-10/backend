package com.ilro.backend.infra.map;

import java.util.List;
import java.util.OptionalInt;

// 지도 계산 - 방문 가능 시간(BE-09)·이동시간 반영(BE-10)이 씀, 실제 지도 연동은 BE-15
// 계산할 수 없으면 빈 값·UNKNOWN으로 돌려줌 - 0분이나 '지역 안'으로 바꾸지 않음 (명세 3.5)
public interface MapClient {

    // 자동차 이동시간(분)
    OptionalInt carTravelMinutes(GeoPoint from, GeoPoint to);

    // 좌표가 업체 서비스 지역(owner_service_area.region_code 목록) 안인지
    ServiceAreaMatch matchServiceArea(GeoPoint point, List<String> regionCodes);
}
