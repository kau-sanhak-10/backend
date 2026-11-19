package com.ilro.backend.domain.notification.service;

import com.ilro.backend.domain.notification.entity.Notification;
import com.ilro.backend.domain.notification.entity.NotificationChannel;
import com.ilro.backend.domain.notification.entity.NotificationSendStatus;
import com.ilro.backend.domain.notification.entity.PushSubscription;
import com.ilro.backend.domain.notification.repository.NotificationRepository;
import com.ilro.backend.domain.notification.repository.PushSubscriptionRepository;
import com.ilro.backend.domain.notification.service.DeliveryPlanner.Plan;
import com.ilro.backend.domain.notification.service.DeliveryPlanner.Push;
import com.ilro.backend.domain.notification.service.DeliveryPlanner.Skip;
import com.ilro.backend.domain.notification.service.DeliveryPlanner.Sms;
import com.ilro.backend.infra.push.WebPushResult;
import com.ilro.backend.infra.push.WebPushSender;
import com.ilro.backend.infra.sms.SmsSendResult;
import com.ilro.backend.infra.sms.SmsSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// 쌓인 알림을 보내는 발송기 (명세 5.10, BE-14) - 예약 저장과 같은 트랜잭션에서 만든 PENDING 행을 저장이 끝난 뒤 따로 보냄
// 한 건씩: ① 읽기 트랜잭션에서 최신 상태로 판단(DeliveryPlanner) ② 트랜잭션 밖에서 발송 ③ 쓰기 트랜잭션에서 결과 저장
//   외부 호출(문자·푸시)을 트랜잭션 안에서 하지 않음 - DB 연결·잠금을 오래 잡지 않게
// 재시도: 실패하면 FAILED, 생성 1분 뒤·5분 뒤에 다시 (3회까지). 한 건이 터져도 나머지는 계속
// 앱 안 알림(IN_APP)은 보낼 것이 없어 고르지 않음 - 목록(B-26)에 보이는 것으로 끝
// 서버가 발송 직후·저장 직전에 죽으면 다음에 한 번 더 갈 수 있음 (서버 1대 구조에서 받아들인 한계)
@Slf4j
@Component
public class NotificationDispatcher {

    static final Duration SECOND_ATTEMPT_AFTER = Duration.ofMinutes(1);
    static final Duration THIRD_ATTEMPT_AFTER = Duration.ofMinutes(5);
    static final List<NotificationChannel> CHANNELS = List.of(NotificationChannel.SMS, NotificationChannel.WEB_PUSH);
    static final List<NotificationSendStatus> RETRYABLE =
            List.of(NotificationSendStatus.PENDING, NotificationSendStatus.FAILED);

    private final NotificationRepository notificationRepository;
    private final PushSubscriptionRepository subscriptionRepository;
    private final DeliveryPlanner planner;
    private final SmsSender smsSender;
    private final WebPushSender pushSender;
    private final Clock clock;
    private final int batchSize;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    public NotificationDispatcher(NotificationRepository notificationRepository,
                                  PushSubscriptionRepository subscriptionRepository, DeliveryPlanner planner,
                                  SmsSender smsSender, WebPushSender pushSender, Clock clock,
                                  @Value("${app.notification.dispatch-batch-size}") int batchSize,
                                  PlatformTransactionManager transactionManager) {
        this.notificationRepository = notificationRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planner = planner;
        this.smsSender = smsSender;
        this.pushSender = pushSender;
        this.clock = clock;
        this.batchSize = batchSize;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    // 결과 - 푸시는 없어진 구독 주소도 같이 (지움)
    private enum Outcome { SENT, FAILED, SKIPPED }

    private record Result(Outcome outcome, String reason, List<String> goneEndpoints) {

        static Result of(Outcome outcome, String reason) {
            return new Result(outcome, reason, List.of());
        }
    }

    @Scheduled(initialDelayString = "${app.notification.dispatch-interval}",
            fixedDelayString = "${app.notification.dispatch-interval}")
    public void run() {
        try {
            int handled = dispatchDue();
            if (handled > 0) {
                log.info("Notifications dispatched: count={}", handled);
            }
        } catch (RuntimeException e) {
            log.warn("Notification dispatch round failed", e);
        }
    }

    // 지금 보낼 차례인 알림을 처리 - 처리한 개수 (테스트는 이걸 직접 부름)
    public int dispatchDue() {
        Instant now = clock.instant();
        List<Long> ids = readTx.execute(status -> notificationRepository.findDeliverableIds(CHANNELS, RETRYABLE,
                now.minus(SECOND_ATTEMPT_AFTER), now.minus(THIRD_ATTEMPT_AFTER), PageRequest.of(0, batchSize)));
        int handled = 0;
        for (Long id : ids) {
            try {
                if (dispatch(id)) {
                    handled++;
                }
            } catch (RuntimeException e) {
                // 이 알림만 다음 차례에 다시 - 나머지는 계속
                log.warn("Notification dispatch failed: notificationId={}", id, e);
            }
        }
        return handled;
    }

    // 한 건 - 고른 뒤 다른 곳에서 이미 처리했으면 false
    private boolean dispatch(Long id) {
        Instant now = clock.instant();
        // ① 최신 상태로 판단 (보낼 값만 꺼냄)
        Plan plan = readTx.execute(status -> notificationRepository.findById(id)
                .filter(Notification::isDeliverable)
                .map(notification -> planner.plan(notification, now))
                .orElse(null));
        if (plan == null) {
            return false;
        }
        // ② 트랜잭션 밖에서 발송
        Result result = switch (plan) {
            case Skip skip -> Result.of(Outcome.SKIPPED, skip.reason());
            case Sms sms -> smsSender.send(sms.phone(), sms.text()) == SmsSendResult.SENT
                    ? Result.of(Outcome.SENT, null) : Result.of(Outcome.FAILED, "sms failed");
            case Push push -> push(push.ownerId());
        };
        // ③ 결과 저장 - 없어진 구독은 알림 상태와 상관없이 지움
        writeTx.executeWithoutResult(status -> {
            result.goneEndpoints().forEach(subscriptionRepository::deleteByEndpoint);
            notificationRepository.findById(id)
                    .filter(Notification::isDeliverable)
                    .ifPresent(notification -> apply(notification, result.outcome()));
        });
        if (result.outcome() != Outcome.SENT) {
            log.info("Notification {}: notificationId={}, reason={}", result.outcome(), id, result.reason());
        }
        return true;
    }

    // 사장님의 모든 기기로 - 하나라도 받으면 SENT, 살아 있는 구독이 없으면 SKIPPED, 나머지 실패면 FAILED
    private Result push(Long ownerId) {
        List<String> endpoints = readTx.execute(status -> subscriptionRepository.findByOwnerId(ownerId).stream()
                .map(PushSubscription::getEndpoint)
                .toList());
        if (endpoints.isEmpty()) {
            // 권한 거부·미지원 브라우저 - 앱 안 알림(목록)으로만 알림
            return Result.of(Outcome.SKIPPED, "no push subscription");
        }
        boolean sent = false;
        boolean failed = false;
        List<String> gone = new ArrayList<>();
        for (String endpoint : endpoints) {
            switch (pushSender.send(endpoint)) {
                case SENT -> sent = true;
                case GONE -> gone.add(endpoint);
                case FAILED -> failed = true;
            }
        }
        if (sent) {
            return new Result(Outcome.SENT, null, gone);
        }
        return failed ? new Result(Outcome.FAILED, "push failed", gone)
                : new Result(Outcome.SKIPPED, "all subscriptions gone", gone);
    }

    private static void apply(Notification notification, Outcome outcome) {
        switch (outcome) {
            case SENT -> notification.markSent();
            case FAILED -> notification.markFailed();
            case SKIPPED -> notification.markSkipped();
        }
    }
}