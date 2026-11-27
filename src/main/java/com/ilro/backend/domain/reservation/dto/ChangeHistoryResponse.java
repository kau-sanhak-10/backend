package com.ilro.backend.domain.reservation.dto;

import java.util.List;

// 변경 요청·제안 이력 (C-17, 명세 4.3) - 최근 순, 끝난 변경(확정·거절·실패·만료·종료)도 포함
public record ChangeHistoryResponse(Long reservationId, List<ChangeView> changes) {
}
