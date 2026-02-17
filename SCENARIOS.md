# Connection Pool Exhaustion → Alert → Pool Resize 시나리오

OpenShift에 배포된 Robot Shop의 Shipping 서비스에서 DB connection pool 고갈 → Instana 알림 → pool 동적 확장으로 해결하는 데모 시나리오입니다.

## 시나리오 흐름

```
정상 (pool=5) → Pool 고갈 → pool-check 500 에러 (Instana 알림) → Pool 확장 (size=20) → pool-check 200 정상 → 정리
```

## 엔드포인트

| 엔드포인트 | 설명 |
|-----------|------|
| `GET /scenario/pool-status` | 현재 pool 상태 조회 (total/active/idle/waiting) |
| `GET /scenario/pool-check` | pool 상태 확인 — idle=0이면 500 에러 반환 (Instana 알림용) |
| `GET /scenario/pool-exhaustion` | pool의 모든 connection을 SLEEP 쿼리로 점유 (기본 300초) |
| `GET /scenario/pool-resize?size=20` | HikariCP pool 크기를 동적으로 증가 |
| `GET /scenario/pool-release` | 점유 중인 connection 해제 |

## 실행 순서

```bash
# Web Gateway 경유 (권장)
BASE_URL="http://<web-host>:8080/api/shipping"

# 직접 호출
# BASE_URL="http://<shipping-host>:8080"

# 1. 정상 상태 확인
curl "$BASE_URL/scenario/pool-check"       # → 200 OK, "Connection pool is healthy"
curl "$BASE_URL/scenario/pool-status"      # → maximumPoolSize=5, activeConnections=0

# 2. Pool 고갈 실행
curl "$BASE_URL/scenario/pool-exhaustion"
# → 5개 connection 모두 SLEEP 쿼리로 점유
curl "$BASE_URL/scenario/pool-status"      # → activeConnections=5, idleConnections=0

# 3. 에러 확인 (이 시점에서 Instana 알림 트리거)
curl "$BASE_URL/scenario/pool-check"
# → 500 "Connection pool exhausted"
# → Instana에서 에러 이벤트 감지

# 4. Pool 동적 확장으로 해결
curl "$BASE_URL/scenario/pool-resize?size=20"
# → pool 크기 5 → 20으로 확장
curl "$BASE_URL/scenario/pool-check"       # → 200 OK (문제 해결!)

# 5. 정리
curl "$BASE_URL/scenario/pool-release"
# → 점유 스레드 interrupt, connection 해제
```

## 설정

### application.properties

```properties
# 작은 pool로 시작 (고갈 데모용)
spring.datasource.hikari.maximum-pool-size=5
# Pool 소진 시 빠르게 에러 발생 (5초)
spring.datasource.hikari.connection-timeout=5000
```

## Instana 알림 설정 가이드

### Event 기반 알림

1. **Instana UI** → **Settings** → **Alerts**
2. **New Alert** 생성
3. 조건 설정:
   - **Event Type**: Error / Exception
   - **Service**: shipping
   - **Filter**: `error.message CONTAINS "Connection pool exhausted"`
4. **Alert Channel** 설정 (Slack, Email 등)

### Custom Event 설정 (권장)

1. **Settings** → **Events** → **New Event**
2. 설정:
   - **Name**: "Connection Pool Exhaustion"
   - **Entity Type**: JVM
   - **Condition**: Built-in metric `hikaricp.connections.active` equals `hikaricp.connections.max`
   - 또는 Application Perspective에서 에러율 기반 조건 설정
3. 해당 Event를 Alert에 연결

## 복구

- `pool-release` 호출 시 점유 connection이 해제됩니다
- 서비스를 재시작하면 pool size가 기본값(5)으로 복원됩니다
- SLEEP 쿼리는 `sleepSeconds` 파라미터 시간(기본 300초) 후 자동 종료됩니다
