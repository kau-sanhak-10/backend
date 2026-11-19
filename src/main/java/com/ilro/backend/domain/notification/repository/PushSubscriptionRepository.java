package com.ilro.backend.domain.notification.repository;

import com.ilro.backend.domain.notification.entity.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    // 구독 등록 (B-27, 명세 5.6) - 브라우저 구독 주소(endpoint)는 하나만 (uq_push_subscription_endpoint)
    // 이미 있으면 새로 만들지 않고 키·브라우저 정보를 갱신, 다른 사장님이 같은 브라우저로 올리면 그 계정으로 옮김
    // 한 문장(ON CONFLICT)이라 같은 주소로 동시에 와도 행은 하나만 남음
    @Modifying
    @Query(value = "INSERT INTO push_subscription (owner_id, endpoint, p256dh, auth, user_agent) "
            + "VALUES (:ownerId, :endpoint, :p256dh, :auth, :userAgent) "
            + "ON CONFLICT (endpoint) DO UPDATE SET owner_id = EXCLUDED.owner_id, p256dh = EXCLUDED.p256dh, "
            + "auth = EXCLUDED.auth, user_agent = EXCLUDED.user_agent", nativeQuery = true)
    int upsert(@Param("ownerId") Long ownerId, @Param("endpoint") String endpoint, @Param("p256dh") String p256dh,
               @Param("auth") String auth, @Param("userAgent") String userAgent);

    // 구독 해제 - 이 사장님 구독일 때만 지움 (다른 업체 구독은 건드리지 않음), 지운 개수
    @Modifying
    @Query("delete from PushSubscription s where s.endpoint = :endpoint and s.owner.id = :ownerId")
    int deleteByEndpointAndOwnerId(@Param("endpoint") String endpoint, @Param("ownerId") Long ownerId);
}