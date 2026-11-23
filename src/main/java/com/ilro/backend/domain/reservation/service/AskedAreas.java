package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.reservation.service.BookingEligibility.ServiceAreaJudge;
import com.ilro.backend.infra.map.GeoPoint;
import com.ilro.backend.infra.map.ServiceAreaMatch;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 한 요청 안의 서비스 지역 답을 기억 - 업체를 잠근 뒤에는 기억한 답만 씀 (지도를 부르지 않음, 명세 1.7)
final class AskedAreas implements ServiceAreaJudge {

    private final ServiceAreaJudge source;
    private final Map<List<Object>, ServiceAreaMatch> asked = new HashMap<>();

    AskedAreas(ServiceAreaJudge source) {
        this.source = source;
    }

    @Override
    public ServiceAreaMatch match(GeoPoint point, List<String> regionCodes) {
        return asked.computeIfAbsent(List.of(point, List.copyOf(regionCodes)), key -> source.match(point, regionCodes));
    }

    AskedAreas askedOnly() {
        Map<List<Object>, ServiceAreaMatch> known = Map.copyOf(asked);
        return new AskedAreas((point, regionCodes) -> {
            ServiceAreaMatch match = known.get(List.of(point, List.copyOf(regionCodes)));
            if (match == null) {
                throw new NotAskedException();
            }
            return match;
        });
    }

    // 잠그는 사이 방문 주소나 서비스 지역이 바뀜 - 다시 고르게 함
    static final class NotAskedException extends RuntimeException {

        NotAskedException() {
            super("잠그기 전에 확인하지 않은 서비스 지역", null, false, false);
        }
    }
}
