package com.example.mafiagame.settlement.repository;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.mafiagame.settlement.domain.DailySettlement;

/**
 * 일일 정산 JPA 리포지토리.
 */
public interface DailySettlementRepository extends JpaRepository<DailySettlement, Long> {

    /**
     * 특정 날짜의 정산 조회.
     */
    Optional<DailySettlement> findBySettlementDate(LocalDate date);
}
