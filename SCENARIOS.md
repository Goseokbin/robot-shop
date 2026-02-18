# SRE 데모 시나리오

OpenShift에 배포된 Robot Shop의 Shipping 서비스를 활용한 SRE 데모 시나리오 모음입니다.

---

# 시나리오 1: Connection Pool Exhaustion → Alert → Pool Resize

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

---

# 시나리오 2: Memory Leak → OOMKilled → oc set resources 복구

Shipping 서비스에서 메모리 누수 → Pod OOMKilled → `oc` 커맨드로 원인 분석 및 리소스 상향으로 복구하는 시나리오입니다.

## 시나리오 흐름

```
정상 → memory-leak 유발 → memory-check 500 (Instana 알림) → OOMKilled/CrashLoopBackOff
→ oc describe (원인 확인) → oc logs --previous (로그 확인) → oc set resources (리소스 상향) → 복구
```

## 엔드포인트

| 엔드포인트 | 설명 |
|-----------|------|
| `GET /scenario/memory-check` | 힙 사용률 확인 — 80% 초과 시 500 에러 (Instana 알림용) |
| `GET /scenario/memory-leak?chunkMB=25&count=20` | chunkMB×count MB 만큼 메모리 할당 (static List, GC 불가) |

## JVM / K8s 설정

- **JVM**: `-Xmx768m` (Dockerfile)
- **K8s memory limit**: `1000Mi`
- memory-leak으로 500MB 할당 시 힙 사용률 80% 초과 → memory-check 500 에러
- 추가 할당 또는 시간 경과 시 OOMKilled 발생

## 실행 순서

```bash
BASE_URL="http://<web-host>:8080/api/shipping"

# 1. 정상 상태 확인
curl "$BASE_URL/scenario/memory-check"
# → 200 OK, "Heap memory is healthy"

# 2. 메모리 누수 유발
curl "$BASE_URL/scenario/memory-leak?chunkMB=25&count=20"
# → 500MB 할당, 힙 사용률 급증

# 3. 장애 확인 (Instana 알림 트리거)
curl "$BASE_URL/scenario/memory-check"
# → 500 "Heap memory critical"

# 4. Pod 상태 확인
oc get pods -l service=shipping
# → OOMKilled 또는 CrashLoopBackOff 상태

# 5. 원인 분석
oc describe pod <shipping-pod>
# → Last State: Terminated, Reason: OOMKilled

oc logs <shipping-pod> --previous
# → "HEAP CRITICAL: usage=..." 로그 확인

# 6. Resource limit 상향으로 복구
oc set resources deployment/shipping --limits=memory=2Gi --requests=memory=1Gi
# → Deployment 변경으로 새 Pod 자동 rollout

# 7. Rollout 확인
oc rollout status deployment/shipping
# → 새 Pod 정상 기동

# 8. 복구 확인
oc get pods -l service=shipping              # → Running, RESTARTS 0 (새 Pod)
curl "$BASE_URL/scenario/memory-check"       # → 200 OK
```

## 복구 원리

1. `oc set resources`로 memory limit을 상향하면 Deployment spec이 변경됨
2. K8s가 자동으로 새 Pod을 rollout (기존 Pod 종료 → 새 Pod 생성)
3. 새 Pod은 깨끗한 JVM으로 시작 → 메모리 누수 없음 + 더 큰 limit 적용
4. 근본 원인(코드의 메모리 누수)은 별도 코드 수정이 필요하지만, 운영 중 즉각 대응으로 서비스 복구

---

# 시나리오 3: Payment 결제 점검 모드 (flagd Feature Flag)

Payment Pod 장애 시 flagd Feature Flag를 통해 **결제시스템 점검 모드**로 전환하여 신규 결제를 차단하는 시나리오입니다.

## 시나리오 흐름

```
정상 → flagd payment-maintenance=true → 5초 내 점검 모드 전환
→ POST /pay/<id> → 503 "Payment system is under maintenance"
→ flagd payment-maintenance=false → 결제 정상 재개
```

## 엔드포인트

| 엔드포인트 | 설명 |
|-----------|------|
| `GET /maintenance-status` | 현재 점검 모드 상태 확인 (`{"maintenance": true/false}`) |
| `POST /pay/<id>` | 점검 모드 시 503 반환, 정상 시 결제 처리 |

## 실행 순서

### K8s / OpenShift

```bash
BASE_URL="http://<web-host>:8080/api/payment"

# 1. 정상 상태 확인
curl "$BASE_URL/maintenance-status"    # → {"maintenance": false}

# 2. Flag ON — 점검 모드 전환
oc edit configmap flagd-config
# "payment-maintenance" 항목의 "defaultVariant": "false" → "true"

# 3. 5초 내 점검 모드 전환 확인
curl "$BASE_URL/maintenance-status"    # → {"maintenance": true}

# 4. 결제 차단 확인
curl -X POST "$BASE_URL/pay/test" -H 'Content-Type: application/json' -d '{}'
# → 503 {"error": "Payment system is under maintenance"}

# 5. Flag OFF — 정상 복구
oc edit configmap flagd-config
# "payment-maintenance" 항목의 "defaultVariant": "true" → "false"

# 6. 결제 정상 재개 확인
curl "$BASE_URL/maintenance-status"    # → {"maintenance": false}
```

### Docker Compose

```bash
# 1. flagd-config.json에서 payment-maintenance defaultVariant → "true"
vi flagd-config.json

# 2. 5초 후 확인
curl http://localhost:8080/api/payment/maintenance-status    # → {"maintenance": true}
curl -X POST http://localhost:8080/api/payment/pay/test -H 'Content-Type: application/json' -d '{}'
# → 503

# 3. defaultVariant → "false"로 되돌리기 → 결제 정상
```

---

# flagd Feature Flag 연동 (자동 시나리오 유발)

위 시나리오들을 curl 수동 호출 대신 **flagd Feature Flag**로 자동 유발할 수 있습니다.

- Flag ON → 5초 내 자동으로 장애 유발 (1회)
- Flag OFF → triggered 상태 리셋 (다시 ON하면 재실행 가능)
- flagd 미연결 시 무시 (graceful degradation)

## 아키텍처

```
flagd (port 8016, OFREP REST API)
  ↑ flag config (ConfigMap / JSON file)

shipping app
  └─ ScenarioFlagWatcher (@Scheduled, 5초 간격)
       ├─ POST /ofrep/v1/evaluate/flags/scenario-memory-leak
       │    → true: memory leak 유발 (1회)
       └─ POST /ofrep/v1/evaluate/flags/scenario-pool-exhaustion
            → true: pool exhaustion 유발 (1회)
```

## Feature Flag 목록

| Flag Key | 설명 |
|----------|------|
| `scenario-memory-leak` | ON → 메모리 누수 자동 유발 (25MB × 20 = 500MB) |
| `scenario-pool-exhaustion` | ON → DB connection pool 고갈 자동 유발 |
| `payment-maintenance` | ON → Payment 결제 점검 모드 (POST /pay 503 반환) |

## K8s / OpenShift 환경 사용법

flagd는 shipping Pod의 sidecar로 실행되며, ConfigMap에서 flag 설정을 읽습니다.

### 시나리오 유발 (Memory Leak 예시)

```bash
# 1. ConfigMap 수정 — defaultVariant를 "true"로 변경
oc edit configmap flagd-config
# "scenario-memory-leak" 항목의 "defaultVariant": "false" → "true"

# 또는 파일로 수정 후 적용
oc apply -f flagd-configmap.yaml

# 2. 5초 내 shipping이 flag 감지 → 자동 메모리 누수 유발
# 로그 확인:
oc logs -f <shipping-pod> -c shipping | grep FlagWatcher

# 3. 장애 확인
curl $BASE_URL/scenario/memory-check    # → 500 "Heap memory critical"

# 4. Instana에서 알림 확인 후 원인 분석
oc describe pod <shipping-pod>
oc logs <shipping-pod> --previous
```

### 시나리오 중단 및 복구

```bash
# 1. Flag OFF — defaultVariant를 "false"로 변경
oc edit configmap flagd-config
# "defaultVariant": "true" → "false"

# 2. 리소스 상향 (메모리 누수의 경우)
oc set resources deployment/shipping --limits=memory=2Gi

# 3. 새 Pod 기동 → flag OFF → 정상 동작
oc rollout status deployment/shipping
curl $BASE_URL/scenario/memory-check    # → 200 OK
```

## Docker Compose 환경 사용법

```bash
# 시나리오 유발: flagd-config.json에서 defaultVariant를 "true"로 변경
# flagd가 파일 변경을 자동 감지합니다.
vi flagd-config.json
# "defaultVariant": "false" → "true"

# 5초 후 자동 유발 확인
curl http://localhost:8080/api/shipping/scenario/memory-check    # → 500

# 복구: defaultVariant를 "false"로 되돌리기
vi flagd-config.json
# "defaultVariant": "true" → "false"
```
