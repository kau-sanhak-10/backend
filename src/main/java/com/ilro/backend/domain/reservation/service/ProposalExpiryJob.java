package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.reservation.entity.ReservationChange;
import com.ilro.backend.domain.reservation.entity.ReservationChangeStatus;
import com.ilro.backend.domain.reservation.repository.ReservationChangeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

// 기한이 지난 시간 변경 제안을 EXPIRED로 (명세 2.4·5.3 '만료 | 서버', BE-16)
// 예약 시간·주소는 그대로 두고 변경만 끝냄 - 그 뒤 고객은 새 요청, 사장님은 새 제안을 만들 수 있음
// 수락(ReservationChangeService.accept)과 같은 업체 잠금 안에서 다시 확인 - 동시에 오면 먼저 잠근 쪽 결과만 남음
//   수락이 먼저면 APPLIED·FAILED라 건너뛰고, 만료가 먼저면 수락은 409 CHANGE_ALREADY_RESOLVED
// 이 작업이 돌기 전에 들어온 수락도 기한을 다시 보므로(지난 제안은 409 CHANGE_EXPIRED) 간격은 화면 표시용
@Slf4j
@Component
@RequiredArgsConstructor
public class ProposalExpiryJob {

    // 한 번에 처리할 제안 수 - 남은 것은 다음 차례에
    static final int BATCH_SIZE = 100;

    private final ReservationChangeRepository changeRepository;
    private final ScheduleWriter writer;
    private final Clock clock;

    private record Due(Long changeId, Long ownerId) {
    }

    @Scheduled(initialDelayString = "${app.booking.proposal-expiry-interval}",
            fixedDelayString = "${app.booking.proposal-expiry-interval}")
    public void run() {
        try {
            int expired = expireDue();
            if (expired > 0) {
                log.info("Expired change proposals: count={}", expired);
            }
        } catch (RuntimeException e) {
            log.warn("Change proposal expiry failed", e);
        }
    }

    // 지금 기한이 지난 제안을 만료 - 만료한 개수
    public int expireDue() {
        Instant now = clock.instant();
        List<Due> due = writer.read(() -> changeRepository.findDueProposals(now, PageRequest.of(0, BATCH_SIZE))
                .stream()
                .map(change -> new Due(change.getId(), change.getReservation().getOwner().getId()))
                .toList());
        int expired = 0;
        for (Due target : due) {
            try {
                if (Boolean.TRUE.equals(writer.locked(target.ownerId(), owner -> expire(target.changeId())))) {
                    expired++;
                }
            } catch (RuntimeException e) {
                // 업체 잠금 대기 초과 등 - 다음 차례에 다시
                log.warn("Change proposal expiry skipped: changeId={}", target.changeId(), e);
            }
        }
        return expired;
    }

    // 잠근 뒤 다시 읽어 아직 기한 지난 제안일 때만 - 수락과 같은 기준 (기한 = 이 시각부터 수락 불가)
    private boolean expire(Long changeId) {
        Instant now = clock.instant();
        ReservationChange change = changeRepository.findById(changeId).orElse(null);
        if (change == null || change.getStatus() != ReservationChangeStatus.PROPOSED
                || change.getExpiresAt() == null || change.getExpiresAt().isAfter(now)) {
            return false;
        }
        change.expire(now);
        return true;
    }
}
