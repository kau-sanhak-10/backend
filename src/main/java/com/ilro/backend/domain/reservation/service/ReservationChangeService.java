package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.notification.service.ReservationNotifier;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.entity.OwnerServiceArea;
import com.ilro.backend.domain.owner.repository.OwnerServiceAreaRepository;
import com.ilro.backend.domain.reservation.dto.ChangeAcceptRequest;
import com.ilro.backend.domain.reservation.dto.ChangeAcceptResponse;
import com.ilro.backend.domain.reservation.dto.ChangeProposalRequest;
import com.ilro.backend.domain.reservation.dto.ChangeRequestRequest;
import com.ilro.backend.domain.reservation.dto.ChangeSnapshot;
import com.ilro.backend.domain.reservation.dto.OwnerChangeRequestsResponse;
import com.ilro.backend.domain.reservation.dto.ReservationSnapshot;
import com.ilro.backend.domain.reservation.entity.ChangedBy;
import com.ilro.backend.domain.reservation.entity.RequestedBy;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationChange;
import com.ilro.backend.domain.reservation.entity.ReservationChangeCloseReason;
import com.ilro.backend.domain.reservation.entity.ReservationChangeStatus;
import com.ilro.backend.domain.reservation.entity.ReservationChangeType;
import com.ilro.backend.domain.reservation.entity.ReservationHistory;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.domain.reservation.exception.ReservationErrorCode;
import com.ilro.backend.domain.reservation.exception.SlotUnavailableReason;
import com.ilro.backend.domain.reservation.repository.ReservationChangeRepository;
import com.ilro.backend.domain.reservation.repository.ReservationHistoryRepository;
import com.ilro.backend.domain.reservation.repository.ReservationRepository;
import com.ilro.backend.domain.reservation.service.BookingEligibility.ServiceAreaJudge;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import com.ilro.backend.global.exception.ErrorCode;
import com.ilro.backend.infra.map.GeoPoint;
import com.ilro.backend.infra.map.ServiceAreaMatch;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 예약 변경 - 요청·제안·수락·거절·닫기 (C-15·B-19·B-21·C-17, 명세 2.4·5.3, BE-12)
// APPLIED 전까지 예약의 시간·주소는 그대로 - 요청·제안만으로 바꾸지 않고, 거절·만료·실패면 기존 예약 유지
// 모든 단계는 업체를 잠그고 차례로 (명세 1.7). 제안·수락은 지도를 잠그기 전에만 부름 (ScheduleWriter)
// 수락은 한 트랜잭션에서 최종 검사까지 끝내고 APPLIED 또는 FAILED를 저장한 뒤 결과를 돌려줌 (실패도 기록이 남음)
@Service
@RequiredArgsConstructor
public class ReservationChangeService {

    // 한 번에 보여 줄 변경 요청 수
    static final int MAX_CHANGES = 100;
    // 제안 기한 - 제안 후 24시간과 제안 방문 2시간 전 중 빠른 시각 (명세 2.4, 10/24 결정)
    static final Duration PROPOSAL_TTL = Duration.ofHours(24);
    static final Duration PROPOSAL_CUTOFF = Duration.ofHours(2);

    private static final Collection<ReservationChangeStatus> DEFAULT_OWNER_LIST = EnumSet.of(
            ReservationChangeStatus.REQUESTED, ReservationChangeStatus.PROPOSED);

    private final ScheduleWriter writer;
    private final VisitScheduleService visitSchedule;
    private final ReservationRepository reservationRepository;
    private final ReservationChangeRepository changeRepository;
    private final ReservationHistoryRepository historyRepository;
    private final OwnerServiceAreaRepository serviceAreaRepository;
    private final CustomerReservationAccess customerAccess;
    private final ReservationNotifier notifier;
    private final Clock clock;

    // ---------- 1. 고객 변경 요청 (C-15) ----------

    public ChangeSnapshot request(AuthPrincipal principal, Long reservationId, ChangeRequestRequest request) {
        boolean hasNote = request.requestNote() != null && !request.requestNote().isBlank();
        if (!hasNote && request.addressChange() == null) {
            throw invalid("requestNote", "바꾸고 싶은 내용이나 주소가 필요합니다");
        }
        Long ownerId = writer.read(() -> customerAccess.find(principal, reservationId).getOwner().getId());
        return writer.locked(ownerId, owner -> {
            Reservation reservation = customerAccess.find(principal, reservationId);
            requireChangeable(reservation);
            ReservationCancelService.checkVersion(reservation, request.baseVersion());
            requireNoActiveChange(reservation);
            ChangeRequestRequest.AddressChange address = request.addressChange();
            ReservationChange change = changeRepository.saveAndFlush(ReservationChange.builder()
                    .reservation(reservation)
                    .changeType(ReservationChangeType.CHANGE)
                    .requestedBy(RequestedBy.CUSTOMER)
                    .requestNote(hasNote ? request.requestNote().trim() : null)
                    .prevStartAt(reservation.getStartAt())
                    .prevEndAt(reservation.getEndAt())
                    .prevAddress(reservation.getAddress())
                    .proposedAddress(address == null ? null : address.fullAddress())
                    .proposedLat(address == null ? null : address.lat())
                    .proposedLng(address == null ? null : address.lng())
                    .baseVersion(reservation.getVersion())
                    .build());
            notifier.changeRequested(change);
            return ChangeSnapshot.from(change);
        });
    }

    // ---------- 2. 사장님 시간 제안 (B-19·B-21) ----------

    public ChangeSnapshot propose(Long ownerId, Long reservationId, ChangeProposalRequest request) {
        Instant startAt = request.proposedStartAt().toInstant();
        if (startAt.getEpochSecond() % 60 != 0 || startAt.getNano() != 0) {
            throw invalid("proposedStartAt", "분 단위여야 합니다");
        }
        return writer.write(ownerId,
                answers -> {
                    Reservation reservation = ownedReservation(ownerId, reservationId);
                    Proposal proposal = proposal(reservation, request, startAt);
                    throwIfBlocked(evaluate(reservation, proposal.plan(), answers));
                    return null;
                },
                (owner, ignored, answers) -> {
                    Reservation reservation = ownedReservation(ownerId, reservationId);
                    Proposal proposal = proposal(reservation, request, startAt);
                    throwIfBlocked(evaluate(reservation, proposal.plan(), answers));
                    Plan plan = proposal.plan();
                    ReservationChange change = proposal.change();
                    if (change == null) {
                        change = ReservationChange.builder()
                                .reservation(reservation)
                                .changeType(ReservationChangeType.CHANGE)
                                .requestedBy(RequestedBy.OWNER)
                                .prevStartAt(reservation.getStartAt())
                                .prevEndAt(reservation.getEndAt())
                                .prevAddress(reservation.getAddress())
                                .baseVersion(reservation.getVersion())
                                .build();
                    }
                    change.propose(plan.startAt(), plan.endAt(), plan.addressChanged() ? plan.address() : null,
                            plan.addressChanged() ? plan.lat() : null, plan.addressChanged() ? plan.lng() : null,
                            proposal.expiresAt());
                    change = changeRepository.saveAndFlush(change);
                    notifier.changeProposed(change);
                    return ChangeSnapshot.from(change);
                });
    }

    // 제안 대상과 검사할 시간·주소
    private record Proposal(ReservationChange change, Plan plan, Instant expiresAt) {
    }

    private Proposal proposal(Reservation reservation, ChangeProposalRequest request, Instant startAt) {
        requireChangeable(reservation);
        ReservationCancelService.checkVersion(reservation, request.baseVersion());
        ReservationChange change = null;
        if (request.changeId() != null) {
            change = changeOf(reservation, request.changeId());
            // 고객 요청에 답하거나(REQUESTED) 아직 답이 없는 제안을 새 시간으로 바꿈(PROPOSED)
            if (change.getStatus() != ReservationChangeStatus.REQUESTED
                    && change.getStatus() != ReservationChangeStatus.PROPOSED) {
                throw changeError(ReservationErrorCode.CHANGE_ALREADY_RESOLVED, change);
            }
        } else {
            requireNoActiveChange(reservation);
        }
        boolean addressChanged = request.applyAddressChange();
        if (addressChanged && (change == null || change.getProposedAddress() == null)) {
            throw invalid("applyAddressChange", "고객이 요청한 주소 변경이 없습니다");
        }
        Instant now = clock.instant();
        Instant expiresAt = min(now.plus(PROPOSAL_TTL), startAt.minus(PROPOSAL_CUTOFF));
        // 방문 2시간 전보다 가까우면 기한이 없음 - 문자로 기다리지 않고 고객과 직접 조율
        if (!expiresAt.isAfter(now)) {
            throw invalid("proposedStartAt", "방문 2시간 전보다 가까운 시간은 고객과 직접 조율해 주세요");
        }
        Instant endAt = startAt.plus(Duration.ofMinutes(workMinutes(reservation)));
        Plan plan = addressChanged
                ? new Plan(startAt, endAt, true, change.getProposedAddress(), change.getProposedLat(),
                change.getProposedLng())
                : new Plan(startAt, endAt, false, reservation.getAddress(), reservation.getLat(), reservation.getLng());
        return new Proposal(change, plan, expiresAt);
    }

    // ---------- 3. 고객 수락 + 최종 검사 + 변경 확정 (C-17) ----------

    public ChangeAcceptResponse accept(AuthPrincipal principal, Long reservationId, Long changeId,
                                       ChangeAcceptRequest request) {
        Long ownerId = writer.read(() -> customerAccess.find(principal, reservationId).getOwner().getId());
        AcceptOutcome outcome = writer.write(ownerId,
                answers -> {
                    // 지도 답만 미리 받아 둠 - 판단은 잠근 뒤
                    Reservation reservation = customerAccess.find(principal, reservationId);
                    ReservationChange change = changeOf(reservation, changeId);
                    if (change.getStatus() == ReservationChangeStatus.PROPOSED && reservation.isConfirmed()) {
                        evaluate(reservation, Plan.of(change, reservation), answers);
                    }
                    return null;
                },
                (owner, ignored, answers) -> acceptLocked(principal, reservationId, changeId, request, answers));
        // 실패(FAILED)·만료(EXPIRED)도 저장한 뒤 오류로 알림
        if (outcome.error() != null) {
            throw outcome.error();
        }
        return outcome.response();
    }

    private record AcceptOutcome(ChangeAcceptResponse response, BusinessException error) {
    }

    private AcceptOutcome acceptLocked(AuthPrincipal principal, Long reservationId, Long changeId,
                                       ChangeAcceptRequest request, ScheduleWriter.MapAnswers answers) {
        Reservation reservation = customerAccess.find(principal, reservationId);
        ReservationChange change = changeOf(reservation, changeId);
        // 같은 수락을 다시 받음 - 처음 결과
        if (change.getStatus() == ReservationChangeStatus.APPLIED) {
            return new AcceptOutcome(accepted(change, reservation), null);
        }
        // 버전부터 - 취소와 동시에 오면 먼저 끝난 쪽이 버전을 올려 뒤의 요청은 버전 충돌 (명세 5.4)
        ReservationCancelService.checkVersion(reservation, request.baseVersion());
        if (change.getStatus() == ReservationChangeStatus.REQUESTED) {
            throw changeError(ReservationErrorCode.CHANGE_NOT_PROPOSED, change);
        }
        if (!change.isActive()) {
            throw changeError(ReservationErrorCode.CHANGE_ALREADY_RESOLVED, change);
        }
        Instant now = clock.instant();
        if (change.getExpiresAt() == null || !change.getExpiresAt().isAfter(now)) {
            change.expire(now);
            changeRepository.flush();
            return new AcceptOutcome(null, changeError(ReservationErrorCode.CHANGE_EXPIRED, change));
        }
        requireChangeable(reservation);
        if (change.getBaseVersion() != reservation.getVersion()) {
            throw new BusinessException(ReservationErrorCode.RESERVATION_VERSION_CONFLICT,
                    Collections.singletonMap("reservation", ReservationSnapshot.from(reservation)));
        }

        Plan plan = Plan.of(change, reservation);
        Verdict verdict;
        try {
            verdict = evaluate(reservation, plan, answers);
        } catch (TravelTimes.NotAskedException | AskedAreas.NotAskedException e) {
            verdict = Verdict.slot(SlotUnavailableReason.TRAVEL_CONFLICT);
        }
        if (!verdict.ok()) {
            change.fail(now);
            changeRepository.flush();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("reservation", ReservationSnapshot.from(reservation));
            result.put("change", ChangeSnapshot.from(change));
            if (verdict.reason() != null) {
                result.put("reason", verdict.reason());
            }
            return new AcceptOutcome(null, new BusinessException(verdict.code(), result));
        }

        reservation.reschedule(plan.startAt(), plan.endAt(), plan.address(), plan.lat(), plan.lng(),
                verdict.travelMinutes());
        change.apply(now);
        historyRepository.save(ReservationHistory.builder()
                .reservation(reservation)
                .fromStatus(ReservationStatus.CONFIRMED)
                .toStatus(ReservationStatus.CONFIRMED)
                .changedBy(ChangedBy.CUSTOMER)
                .build());
        notifier.changeApplied(change);
        reservationRepository.flush();
        return new AcceptOutcome(accepted(change, reservation), null);
    }

    private static ChangeAcceptResponse accepted(ReservationChange change, Reservation reservation) {
        return new ChangeAcceptResponse(ChangeSnapshot.from(change), ReservationSnapshot.from(reservation));
    }

    // ---------- 고객 거절 (C-17) ----------

    public ChangeSnapshot reject(AuthPrincipal principal, Long reservationId, Long changeId) {
        Long ownerId = writer.read(() -> customerAccess.find(principal, reservationId).getOwner().getId());
        return writer.locked(ownerId, owner -> {
            Reservation reservation = customerAccess.find(principal, reservationId);
            ReservationChange change = changeOf(reservation, changeId);
            if (change.getStatus() == ReservationChangeStatus.REJECTED) {
                return ChangeSnapshot.from(change);
            }
            if (change.getStatus() == ReservationChangeStatus.REQUESTED) {
                throw changeError(ReservationErrorCode.CHANGE_NOT_PROPOSED, change);
            }
            if (!change.isActive()) {
                throw changeError(ReservationErrorCode.CHANGE_ALREADY_RESOLVED, change);
            }
            change.reject(clock.instant());
            changeRepository.flush();
            return ChangeSnapshot.from(change);
        });
    }

    // ---------- 사장님 요청 닫기·목록 (B-21) ----------

    // 고객 요청이나 답을 기다리는 제안을 닫음 - 기존 예약 유지
    public ChangeSnapshot close(Long ownerId, Long changeId) {
        return writer.locked(ownerId, owner -> {
            ReservationChange change = changeRepository.findById(changeId)
                    .filter(found -> found.getReservation().getOwner().getId().equals(ownerId))
                    .orElseThrow(() -> new BusinessException(ReservationErrorCode.CHANGE_NOT_FOUND));
            if (change.getStatus() == ReservationChangeStatus.CLOSED
                    && change.getCloseReason() == ReservationChangeCloseReason.OWNER_CLOSED) {
                return ChangeSnapshot.from(change);
            }
            if (!change.isActive()) {
                throw changeError(ReservationErrorCode.CHANGE_ALREADY_RESOLVED, change);
            }
            change.close(ReservationChangeCloseReason.OWNER_CLOSED, ChangedBy.OWNER, clock.instant());
            changeRepository.flush();
            return ChangeSnapshot.from(change);
        });
    }

    // statuses가 없으면 사장님이 볼 일: 고객 요청(REQUESTED)·답 기다리는 제안(PROPOSED)
    public OwnerChangeRequestsResponse list(Long ownerId, Collection<ReservationChangeStatus> statuses) {
        Collection<ReservationChangeStatus> filter = statuses == null || statuses.isEmpty()
                ? DEFAULT_OWNER_LIST : statuses;
        return writer.read(() -> OwnerChangeRequestsResponse.from(
                changeRepository.findByOwner(ownerId, filter, PageRequest.of(0, MAX_CHANGES))));
    }

    // ---------- 검사 ----------

    // 바꿀 시간·주소 - addressChanged면 지역·앞뒤 이동시간을 새 주소로
    private record Plan(Instant startAt, Instant endAt, boolean addressChanged, String address, BigDecimal lat,
                        BigDecimal lng) {

        static Plan of(ReservationChange change, Reservation reservation) {
            boolean addressChanged = change.getProposedAddress() != null;
            return new Plan(change.getProposedStartAt(), change.getProposedEndAt(), addressChanged,
                    addressChanged ? change.getProposedAddress() : reservation.getAddress(),
                    addressChanged ? change.getProposedLat() : reservation.getLat(),
                    addressChanged ? change.getProposedLng() : reservation.getLng());
        }
    }

    // 검사 결과 - 통과면 그 시각의 이동시간, 아니면 응답 코드와 이유
    private record Verdict(ErrorCode code, SlotUnavailableReason reason, Integer travelMinutes) {

        static Verdict ok(Integer travelMinutes) {
            return new Verdict(null, null, travelMinutes);
        }

        static Verdict slot(SlotUnavailableReason reason) {
            return new Verdict(ReservationErrorCode.SLOT_UNAVAILABLE, reason, null);
        }

        static Verdict of(ErrorCode code) {
            return new Verdict(code, null, null);
        }

        boolean ok() {
            return code == null;
        }
    }

    // 명세 5.2 3번 시간 검사 (자기 예약은 빼고), 주소가 바뀌면 서비스 지역도 - 지도 장애는 MapUnavailableException(503)
    private Verdict evaluate(Reservation reservation, Plan plan, ScheduleWriter.MapAnswers answers) {
        GeoPoint destination = GeoPoint.of(plan.lat(), plan.lng()).orElse(null);
        if (plan.addressChanged() && !insideServiceArea(reservation.getOwner(), destination, answers.areas())) {
            return Verdict.of(ReservationErrorCode.OUT_OF_SERVICE_AREA);
        }
        int workMinutes = (int) Duration.between(plan.startAt(), plan.endAt()).toMinutes();
        SlotCheck check = visitSchedule.check(reservation.getOwner(), plan.startAt(), workMinutes, destination,
                reservation.getId(), answers.travel());
        if (check.travelUnknown()) {
            return Verdict.of(ReservationErrorCode.TRAVEL_TIME_UNAVAILABLE);
        }
        if (!check.isAvailable()) {
            return Verdict.slot(check.reason());
        }
        return Verdict.ok(check.travelMinutes());
    }

    private boolean insideServiceArea(Owner owner, GeoPoint destination, ServiceAreaJudge areas) {
        List<String> regionCodes = serviceAreaRepository.findByOwnerIdOrderByIdAsc(owner.getId()).stream()
                .map(OwnerServiceArea::getRegionCode)
                .toList();
        return destination != null && !regionCodes.isEmpty()
                && areas.match(destination, regionCodes) == ServiceAreaMatch.INSIDE;
    }

    private static void throwIfBlocked(Verdict verdict) {
        if (verdict.ok()) {
            return;
        }
        throw verdict.reason() == null ? new BusinessException(verdict.code())
                : new BusinessException(verdict.code(), Map.of("reason", verdict.reason()));
    }

    private Reservation ownedReservation(Long ownerId, Long reservationId) {
        return reservationRepository.findByIdAndOwnerId(reservationId, ownerId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND));
    }

    private ReservationChange changeOf(Reservation reservation, Long changeId) {
        return changeRepository.findById(changeId)
                .filter(change -> change.getReservation().getId().equals(reservation.getId()))
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.CHANGE_NOT_FOUND));
    }

    // 취소·완료 예약은 시간·주소를 바꾸지 않음
    private static void requireChangeable(Reservation reservation) {
        if (!reservation.isConfirmed()) {
            throw new BusinessException(ReservationErrorCode.RESERVATION_NOT_CHANGEABLE,
                    Collections.singletonMap("status", reservation.getStatus()));
        }
    }

    private void requireNoActiveChange(Reservation reservation) {
        changeRepository.findFirstByReservationIdAndStatusIn(reservation.getId(),
                CustomerReservationService.ACTIVE_CHANGE).ifPresent(active -> {
                    throw changeError(ReservationErrorCode.CHANGE_IN_PROGRESS, active);
                });
    }

    private static int workMinutes(Reservation reservation) {
        return reservation.getWorkMinutes() != null ? reservation.getWorkMinutes()
                : (int) Duration.between(reservation.getStartAt(), reservation.getEndAt()).toMinutes();
    }

    private static BusinessException changeError(ReservationErrorCode code, ReservationChange change) {
        return new BusinessException(code, Collections.singletonMap("change", ChangeSnapshot.from(change)));
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(CommonErrorCode.INVALID_INPUT, List.of(new FieldErrorResponse(field, reason)));
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
