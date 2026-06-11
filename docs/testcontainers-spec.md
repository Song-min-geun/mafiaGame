# Testcontainers Spec

## Local Baseline

| Area | Spec |
|---|---|
| Local machine | MacBook Air M2, 16GB RAM, 1TB SSD |
| Docker Desktop memory | 8GB |
| Docker Desktop CPU | 4 cores |
| Default Gradle test JVM | `-Xms256m -Xmx1g` |
| Failover test JVM | `-Xms512m -Xmx2g` |
| Optional GC profile | `./gradlew failoverTest -PenableZgc` |

## Redis Containers

| Container | Purpose | Memory | Eviction |
|---|---|---:|---|
| Core Redis | Game state, timer, lock critical path | 512mb | `noeviction` |
| Support Redis | Session, chat, cache-like auxiliary data | 256mb | `allkeys-lru` |

## Test Strategy

Default tests use the lightweight Core Redis and Support Redis Testcontainers image setup.
Sentinel and failover scenarios should stay in `failoverTest` so the regular test suite remains fast on a 16GB local laptop.

The current application wiring uses `spring.data.redis.*` as the primary Redis connection.
`RedisTestContainerSupport` also exposes `mafiagame.redis.core.*` and `mafiagame.redis.support.*` properties so the tests are ready for a later core/support Redis split.
