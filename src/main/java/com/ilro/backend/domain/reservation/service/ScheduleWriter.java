package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.repository.OwnerRepository;
import com.ilro.backend.domain.reservation.exception.ReservationErrorCode;
import com.ilro.backend.domain.reservation.exception.SlotUnavailableReason;
import com.ilro.backend.domain.reservation.service.BookingEligibility.ServiceAreaJudge;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

// 일정을 바꾸는 쓰기의 공통 순서 (명세 1.7, BE-12) - 수동 예약·변경 제안·변경 수락·차단 시간·취소
// 지도를 쓰는 쓰기(write): 1) 읽기 트랜잭션에서 미리 검사하며 지도 답을 기억
//                          2) 업체 행을 잠근 쓰기 트랜잭션에서 같은 검사를 DB만으로 다시 (지도 답은 1에서 받은 것만)
//   잠그는 사이 앞뒤 방문지·주소가 바뀌어 처음 보는 구간이 생기면 409 SLOT_UNAVAILABLE(TRAVEL_CONFLICT) - 다시 고르게 함
// 지도를 안 쓰는 쓰기(locked): 업체 행을 잠근 쓰기 트랜잭션 하나
// 업체 잠금은 3초 넘게 기다리면 503 (PessimisticLockingFailureException)
@Component
public class ScheduleWriter {

    private final OwnerRepository ownerRepository;
    private final MapLookup mapLookup;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    public ScheduleWriter(OwnerRepository ownerRepository, MapLookup mapLookup,
                          PlatformTransactionManager transactionManager) {
        this.ownerRepository = ownerRepository;
        this.mapLookup = mapLookup;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    // 한 요청에서 쓰는 지도 답 - 서비스 지역과 이동시간
    public static final class MapAnswers {

        private final AskedAreas areas;
        private final TravelTimes travel;

        private MapAnswers(AskedAreas areas, TravelTimes travel) {
            this.areas = areas;
            this.travel = travel;
        }

        public ServiceAreaJudge areas() {
            return areas;
        }

        public TravelTimes travel() {
            return travel;
        }

        private MapAnswers askedOnly() {
            return new MapAnswers(areas.askedOnly(), travel.askedOnly());
        }
    }

    // 잠근 뒤 할 일 - 잠근 업체, 미리 검사 결과, 기억한 지도 답
    @FunctionalInterface
    public interface LockedWork<P, T> {

        T run(Owner owner, P precheck, MapAnswers answers);
    }

    public <P, T> T write(Long ownerId, Function<MapAnswers, P> precheck, LockedWork<P, T> locked) {
        MapAnswers live = new MapAnswers(new AskedAreas(mapLookup::serviceArea), mapLookup.newRequest());
        P checked = readTx.execute(status -> precheck.apply(live));
        MapAnswers asked = live.askedOnly();
        try {
            return writeTx.execute(status -> locked.run(lockOwner(ownerId), checked, asked));
        } catch (TravelTimes.NotAskedException | AskedAreas.NotAskedException e) {
            throw travelConflict();
        }
    }

    public <T> T locked(Long ownerId, Function<Owner, T> work) {
        return writeTx.execute(status -> work.apply(lockOwner(ownerId)));
    }

    public <T> T read(Supplier<T> work) {
        return readTx.execute(status -> work.get());
    }

    public static BusinessException travelConflict() {
        return new BusinessException(ReservationErrorCode.SLOT_UNAVAILABLE,
                Map.of("reason", SlotUnavailableReason.TRAVEL_CONFLICT));
    }

    private Owner lockOwner(Long ownerId) {
        return ownerRepository.findByIdForUpdate(ownerId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
}
