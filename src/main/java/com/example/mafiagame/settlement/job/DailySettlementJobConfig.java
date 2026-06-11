package com.example.mafiagame.settlement.job;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.example.mafiagame.payment.domain.PaymentStatus;
import com.example.mafiagame.payment.repository.PaymentRepository;
import com.example.mafiagame.settlement.domain.DailySettlement;
import com.example.mafiagame.settlement.domain.SettlementStatus;
import com.example.mafiagame.settlement.repository.DailySettlementRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 일일 정산 Spring Batch Job.
 *
 * <p>
 * 전날의 결제 완료 건을 집계하여 DailySettlement 레코드를 생성한다.
 * PG 수수료율(3.3%)을 적용하여 순수익을 계산한다.
 * </p>
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class DailySettlementJobConfig {

    private static final double PG_FEE_RATE = 0.033;

    private final PaymentRepository paymentRepository;
    private final DailySettlementRepository settlementRepository;

    /**
     * 일일 정산 Job 정의.
     */
    @Bean
    public Job dailySettlementJob(JobRepository jobRepository, Step aggregateStep) {
        return new JobBuilder("dailySettlementJob", jobRepository)
                .start(aggregateStep)
                .build();
    }

    /**
     * 결제 집계 Step.
     *
     * <p>
     * Tasklet 방식으로 전날 결제 건을 한 번에 집계한다.
     * 데이터가 대량인 경우 Chunk 방식으로 전환 가능.
     * </p>
     */
    @Bean
    public Step aggregateStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
        return new StepBuilder("aggregateStep", jobRepository)
                .tasklet(aggregateTasklet(), txManager)
                .build();
    }

    /**
     * 집계 Tasklet 구현.
     */
    @Bean
    public Tasklet aggregateTasklet() {
        return (contribution, chunkContext) -> {
            // Job Parameter에서 targetDate를 가져오거나, 없으면 전날 사용
            Map<String, Object> params = chunkContext.getStepContext().getJobParameters();
            String targetDateStr = (String) params.get("targetDate");
            LocalDate targetDate = targetDateStr != null
                    ? LocalDate.parse(targetDateStr)
                    : LocalDate.now().minusDays(1);

            log.info("[정산 Batch] 시작: targetDate={}", targetDate);

            // 중복 정산 방지
            if (settlementRepository.findBySettlementDate(targetDate).isPresent()) {
                log.warn("[정산 Batch] 이미 정산 완료된 날짜: {}", targetDate);
                return RepeatStatus.FINISHED;
            }

            LocalDateTime from = targetDate.atStartOfDay();
            LocalDateTime to = targetDate.atTime(LocalTime.MAX);

            // 전날 결제 완료 건 집계
            var payments = paymentRepository.findBySettledFalseAndStatusAndApprovedAtBetween(
                    PaymentStatus.DONE, from, to,
                    org.springframework.data.domain.Pageable.unpaged());

            long totalSales = 0L;
            int orderCount = 0;

            for (var payment : payments) {
                totalSales += payment.getAmount();
                orderCount++;
                payment.setSettled(true);
            }

            paymentRepository.saveAll(payments);

            // PG 수수료 및 순수익 계산
            long pgFee = Math.round(totalSales * PG_FEE_RATE);
            long netAmount = totalSales - pgFee;

            DailySettlement settlement = DailySettlement.builder()
                    .settlementDate(targetDate)
                    .totalOrderCount(orderCount)
                    .totalSalesAmount(totalSales)
                    .totalRefundAmount(0L)
                    .pgFeeAmount(pgFee)
                    .netAmount(netAmount)
                    .status(SettlementStatus.COMPLETED)
                    .processedAt(LocalDateTime.now())
                    .build();

            settlementRepository.save(settlement);

            log.info("[정산 Batch] 완료: date={}, orders={}, sales={}원, fee={}원, net={}원",
                    targetDate, orderCount, totalSales, pgFee, netAmount);

            return RepeatStatus.FINISHED;
        };
    }
}
