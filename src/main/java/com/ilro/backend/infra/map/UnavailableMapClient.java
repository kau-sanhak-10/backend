package com.ilro.backend.infra.map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.OptionalInt;

// 운영(prod)용 자리 - BE-15 실제 지도 연동 전까지는 지역·이동시간을 모른다고 답함
// 그동안 자동 예약은 시간을 임의로 만들지 않고 사장님 확인으로 넘어감 (명세 3.5)
// BE-15에서 실제 지도 클라이언트를 만들면 이 클래스는 지움
@Component
@Profile("prod")
public class UnavailableMapClient implements MapClient {

    @Override
    public OptionalInt carTravelMinutes(GeoPoint from, GeoPoint to) {
        return OptionalInt.empty();
    }

    @Override
    public ServiceAreaMatch matchServiceArea(GeoPoint point, List<String> regionCodes) {
        return ServiceAreaMatch.UNKNOWN;
    }
}
