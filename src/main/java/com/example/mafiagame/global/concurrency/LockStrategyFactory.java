package com.example.mafiagame.global.concurrency;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;

/**
 * 락 전략 팩토리
 * LockType에 따라 적절한 LockStrategy 구현체를 반환
 */
@Component
@RequiredArgsConstructor
public class LockStrategyFactory {

    private final List<LockStrategy> strategies;
    private final Map<LockType, LockStrategy> strategyMap = new EnumMap<>(LockType.class);

    @PostConstruct
    public void init() {
        for (LockStrategy strategy : strategies) {
            LockType type = LockType.valueOf(strategy.getStrategyName());
            strategyMap.put(type, strategy);
        }
    }

    /**
     * LockType에 해당하는 전략 반환
     * 
     * @param type 락 타입
     * @return 해당 락 전략 구현체
     * @throws IllegalArgumentException 지원하지 않는 락 타입인 경우
     */
    public LockStrategy getStrategy(LockType type) {
        LockStrategy strategy = strategyMap.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("지원하지 않는 락 타입: " + type);
        }
        return strategy;
    }
}
