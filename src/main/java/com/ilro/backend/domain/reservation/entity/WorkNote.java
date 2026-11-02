package com.ilro.backend.domain.reservation.entity;

import com.ilro.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 작업 메모 - 사장님 전용 (B24), 고객 화면·알림·요약에 노출 금지
@Getter
@Entity
@Table(name = "work_note")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkNote extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Builder
    private WorkNote(Reservation reservation, String content) {
        this.reservation = reservation;
        this.content = content;
    }
}
