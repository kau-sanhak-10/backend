package com.ilro.backend.domain.address.service;

import com.ilro.backend.domain.address.exception.AddressErrorCode;
import com.ilro.backend.domain.inquiry.service.InquiryAccess;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.infra.map.AddressSearchClient;
import com.ilro.backend.infra.map.AddressSearchClient.Address;
import com.ilro.backend.infra.map.AddressSearchClient.Region;
import com.ilro.backend.infra.map.MapApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

// 주소·지역 검색 (BE-15) - 고른 결과의 주소·좌표를 방문정보(C-06)·업체 출발지(B-05) 저장에,
// 지역 코드·이름을 서비스 지역(B-06) 저장에 그대로 보냄
// 결과 없음은 빈 목록, 지도 장애는 503 MAP_UNAVAILABLE "잠시 후 다시"
// 지도 호출은 DB 트랜잭션 밖에서 (느린 지도가 DB 연결을 붙잡지 않게)
@Service
@RequiredArgsConstructor
public class AddressSearchService {

    // 한 번에 보여 주는 결과 수
    static final int MAX_RESULTS = 10;

    private final AddressSearchClient client;
    private final InquiryAccess inquiryAccess;

    // 고객 - 문의 토큰이 이 문의 것인지만 확인 (다른 문의 번호면 403·404)
    public List<Address> searchAddressForInquiry(AuthPrincipal principal, Long inquiryId, String query) {
        inquiryAccess.find(principal, inquiryId);
        return searchAddress(query);
    }

    // 사장님 - 업체 토큰은 인터셉터(/api/v1/owner/**)에서 확인
    public List<Address> searchAddress(String query) {
        try {
            return client.searchAddress(query.strip(), MAX_RESULTS);
        } catch (MapApiException e) {
            throw new BusinessException(AddressErrorCode.MAP_UNAVAILABLE);
        }
    }

    public List<Region> searchRegion(String query) {
        try {
            return client.searchRegion(query.strip(), MAX_RESULTS);
        } catch (MapApiException e) {
            throw new BusinessException(AddressErrorCode.MAP_UNAVAILABLE);
        }
    }
}