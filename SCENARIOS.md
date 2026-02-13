# 모니터링 시나리오 (Monitoring Scenarios)

OpenShift/Instana 모니터링 테스트를 위한 시나리오 엔드포인트입니다.

## MySQL 연결 고갈 (Connection Exhaustion)

MySQL의 max_connections를 초과하여 "Too many connections" 에러를 유발하는 시나리오입니다.
Shipping 및 Ratings 서비스가 동일한 MySQL 인스턴스를 사용하므로, 연결이 고갈되면 두 서비스 모두 영향받습니다.

### 호출 방법

**Web Gateway 경유 (권장):**
```bash
# 기본값: 200개 연결, 120초간 유지
curl "http://<web-host>:8080/api/shipping/scenario/mysql-exhaustion"

# 파라미터 지정
curl "http://<web-host>:8080/api/shipping/scenario/mysql-exhaustion?connections=200&hold_seconds=60"
```

**Shipping 서비스 직접 호출:**
```bash
curl "http://<shipping-host>:8080/scenario/mysql-exhaustion?connections=200&hold_seconds=60"
```

### 파라미터

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `connections` | 200 | 생성할 MySQL 연결 수 (MySQL 기본 max_connections=151을 초과하도록 설정) |
| `hold_seconds` | 120 | 각 연결을 유지할 시간(초). SLEEP 쿼리로 연결 점유 |

### 예상 결과

- 초기: 지정된 수만큼 MySQL 연결 생성 시도
- MySQL max_connections(기본 151) 초과 시: "Too many connections" 에러 발생
- Shipping, Ratings 서비스의 DB 요청 실패
- Instana에서 DB 연결 에러, 고 latency 등 모니터링 가능

### 복구

연결은 `hold_seconds` 경과 후 자동으로 해제됩니다. 추가 호출 없이 대기하면 됩니다.
