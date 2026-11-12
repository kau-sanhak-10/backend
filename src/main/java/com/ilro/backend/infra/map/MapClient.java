package com.ilro.backend.infra.map;

import java.util.List;
import java.util.OptionalInt;

// 지도 계산 - 방문 가능 시간(BE-09)·이동시간 반영(BE-10)이 MapLookup을 거쳐 씀, 실제 지도 연동은 BE-15
// 약속 (MapClientContract 테스트로 확인 - 실제 지도도 같은 테스트를 통과해야 함)
// - 이동시간: 자동차 예상 시간을 분 단위 정수로 (초는 올림), 0 이상. 같은 좌표는 0분
// - 계산할 수 없으면 빈 값·UNKNOWN - 0분이나 '지역 안'으로 바꾸지 않음 (명세 3.5)
// - 오류는 예외로 던져도 됨 - MapLookup이 '모름'으로 바꿔 사장님 확인으로 넘김
// - 같은 구간을 다시 묻지 않게 하는 기억은 MapLookup이 함 (여기서 따로 하지 않아도 됨)
public interface MapClient {

    // 자동차 이동시간(분) - from에서 to로 (방향에 따라 다를 수 있음)
    OptionalInt carTravelMinutes(GeoPoint from, GeoPoint to);

    // 좌표가 업체 서비스 지역(owner_service_area.region_code 목록) 안인지 - 지역을 안 정했으면 INSIDE가 아님
    ServiceAreaMatch matchServiceArea(GeoPoint point, List<String> regionCodes);
}
