# nGrinder 부하 테스트 스크립트

## 📁 파일 목록

| 파일 | 설명 |
|------|------|
| `ConcurrencyLoadTest.groovy` | 메인 부하 테스트 (단일 전략) |
| `StrategyComparisonTest.groovy` | 전략별 비교 테스트 |

---

## 🚀 사용법

### 1. ConcurrencyLoadTest.groovy (권장)

```groovy
// 스크립트 내 설정 변경
static String BASE_URL = "http://your-server:8080"
static String LOCK_TYPE = "REDISSON_PUBSUB"  // 테스트할 전략
static Long USER_ID = 1L
```

**테스트 순서**:
1. `LOCK_TYPE = "NONE"` → 테스트 실행 → 결과 기록
2. `LOCK_TYPE = "SYNCHRONIZED"` → 테스트 실행 → 결과 기록
3. (반복...)

### 2. nGrinder 설정 예시

| 설정 | 값 | 설명 |
|------|-----|------|
| Agent | 1 | 단일 에이전트로 시작 |
| Vuser per Agent | 100 | 동시 사용자 수 |
| Duration | 60초 | 테스트 지속 시간 |
| Ramp-Up | 10초 | 점진적 부하 증가 |

---

## 📊 결과 비교표 템플릿

| 전략 | TPS | Mean Time (ms) | Error Rate (%) | 정합성 |
|------|-----|----------------|----------------|--------|
| NONE | | | | |
| SYNCHRONIZED | | | | |
| PESSIMISTIC | | | | |
| OPTIMISTIC | | | | |
| REDISSON_SPIN | | | | |
| REDISSON_PUBSUB | | | | |

---

## ⚠️ 주의사항

1. **MySQL/Redis 실행 필수**: 테스트 전 DB와 Redis 서버 확인
2. **유저 생성**: 테스트 전 `USER_ID`에 해당하는 유저가 존재해야 함
3. **정합성 확인**: 테스트 후 `playCount` 값 확인
   ```bash
   curl http://localhost:8080/api/test/concurrency/playcount/{userId}
   ```
