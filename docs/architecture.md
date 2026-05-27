# Java APM Dashboard v1.12.1 — 아키텍처

## 1. 전체 구성도

```
┌────────────────────────────────────────────────────────────────────────────┐
│                         Java APM Dashboard v1.12.1                          │
│                                                                             │
│  ┌──────────────────┐                                                       │
│  │  monitor.props   │  우선순위: 시스템 속성 > 외부 파일 > 클래스패스 기본값   │
│  └────────┬─────────┘                                                       │
│           │ MonitorConfig.load()                                             │
│           ▼                                                                  │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │                         MonitoringAgent                               │  │
│  │  - 생명주기 관리 (start / stop)                                         │  │
│  │  - ScheduledExecutorService (콘솔 출력 / 원격 폴링 / Agent 폴링)         │  │
│  └──────┬──────────────────┬──────────────────┬──────────────────┬──────┘  │
│         │                  │                  │                  │          │
│  ┌──────▼──────┐  ┌────────▼───────┐  ┌──────▼──────┐  ┌───────▼───────┐ │
│  │JvmMetrics   │  │TransactionTracer│  │RemoteJvm    │  │AgentHttpClient│ │
│  │Collector    │  │+ SpanStorage   │  │Collector    │  │               │ │
│  │(로컬 MXBean) │  │                │  │(JMX RMI)    │  │(HTTP Agent)   │ │
│  │             │  │ · @Trace 추적   │  │ · 5s 폴링   │  │ · 5s 폴링     │ │
│  │ · Heap/GC   │  │ · 수동 Span API │  │ · 자동 재연결│  │ · connect()   │ │
│  │ · Thread    │  │ · 링 버퍼      │  │ · snapshot  │  │ · collect()   │ │
│  │ · CPU/OS    │  │                │  │ · threads   │  │ · proxy 프록시│ │
│  │ · BufferPool│  └────────┬───────┘  │ · requests  │  │               │ │
│  │ · JIT       │           │          │ · dbpools   │  └───────────────┘ │
│  └──────┬──────┘           │          └──────┬──────┘                    │
│         │                  │                 │                            │
│  ┌──────▼──────────────────▼─────────────────▼────────────────────────┐  │
│  │                        MetricsHttpServer                             │  │
│  │          (JDK com.sun.net.httpserver — 외부 라이브러리 없음)            │  │
│  │                                                                      │  │
│  │  /dashboard        /jvm           /threads        /api/thread/{id}  │  │
│  │  /metrics          /health        /traces         /api/config       │  │
│  │  /remote/jvm       /remote/threads /remote/thread/{id}              │  │
│  │  /remote/threaddump /remote/requests /remote/dbpools /remote/status │  │
│  │  /agent/status     /agent/jvm     /agent/threads  /agent/thread/{id}│  │
│  │  /agent/threaddump /agent/deadlocks /agent/requests /agent/dbpools  │
│  /agent/top10                                                        │  │
│  └───────────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────────┘
                        │
          ┌─────────────┼──────────────┐
          │             │              │
┌─────────▼──────┐  ┌───▼──────┐  ┌───▼──────────────────────────┐
│ 브라우저 대시보드│  │Prometheus│  │     대상 JVM (Agent 모드)      │
│ /dashboard      │  │/metrics  │  │                              │
│ - 실시간 차트    │  │scrape    │  │  java -javaagent:            │
│ - 스레드 분석   │  │          │  │    agent.jar=port=7979       │
│ - Thread Dump  │  └──────────┘  │  -jar myapp.jar              │
│ - DB Pool 현황 │               │                              │
│ - HTTP 요청 현황│               │  AgentHttpServer (내장)       │
└────────────────┘               │  GET /agent/threads          │
                                 │  GET /agent/jvm              │
                                 │  GET /agent/threaddump       │
                                 │  GET /agent/requests         │
                                 │  GET /agent/dbpools          │
                                 └──────────────────────────────┘
```

---

## 2. 로컬 모드 데이터 흐름

```
[Your Application / Demo]
        │
        │  MonitoringAgent.builder().config(cfg).build().start()
        ▼
[MonitoringAgent]
        │
        ├─ JvmMetricsCollector.snapshot()  ─────────────────────────────────┐
        │   └── java.lang.management MXBean (로컬 JVM 직접 조회)              │
        │                                                                    │
        ├─ TransactionTracer.trace("op", () -> {...})                        │
        │   └── Span 생성 → SpanStorage 저장                                 │
        │                                                                    ▼
        │                                                      [MetricsHttpServer]
        │                                                             │
        │  브라우저 GET /dashboard ◄────────────────────── HTML 응답  │
        │  브라우저 GET /jvm       ◄──────────── JSON(로컬 메트릭)    │
        │  Prometheus GET /metrics ◄──────────── text/plain           │
        └─────────────────────────────────────────────────────────────┘
```

---

## 3. 원격 JMX 모드 데이터 흐름

```
[대상 JVM]                              [Java APM Dashboard]
 Spring Boot / Tomcat 등
 -Dcom.sun.management.jmxremote         monitor.properties:
 -Dcom.sun.management.jmxremote.port=9999  remote.jmx.enabled=true
 -Dcom.sun.management.jmxremote.ssl=false  remote.jmx.host=10.0.0.5
                                            remote.jmx.port=9999
      │                                          │
      │  JMX RMI (port 9999)                     │
      │◄─────────────────────────────────────────┤
      │                                    [RemoteJvmCollector]
      │                                      - connect()
      │  MemoryMXBean / ThreadMXBean         - collect() 5s 주기
      │  GarbageCollectorMXBean              - snapshot 캐시 (volatile)
      │  OperatingSystemMXBean               - 연결 끊김 시 자동 재연결
      │  Catalina:RequestProcessor MBean     - getRequestProcessors()
      │  com.zaxxer.hikari:PoolStats MBean   - getConnectionPools()
      │─────────────────────────────────────►│
      │  MBean 속성값 응답                         │
                                                  │
                                    [MetricsHttpServer]
                                      GET /remote/jvm      → snapshot JSON
                                      GET /remote/status   → 연결 상태 JSON
                                      GET /remote/threads  → 스레드 목록 JSON
                                      GET /remote/thread/{id} → 전체 스택 JSON
                                      GET /remote/threaddump  → jstack 형식
                                      GET /remote/requests → HTTP 요청 현황
                                      GET /remote/dbpools  → DB 커넥션 풀
                                          │
                                    [브라우저 대시보드]
                                      원격 JVM 섹션 5s 갱신
                                      스레드 테이블 (상태·CPU·잠금·스택)
                                      HTTP 요청 처리 테이블 (URI / 처리시간)
                                      DB 커넥션 풀 섹션 (Active/Idle/Max)
```

---

## 4. Agent HTTP 모드 데이터 흐름

```
[대상 JVM]                                   [Java APM Dashboard]
 java -javaagent:agent.jar=port=7979           monitor.properties:
      -jar myapp.jar                             agent.enabled=true
                                                 agent.host=10.0.0.5
 ┌──────────────────────────┐                   agent.port=7979
 │   ThreadMonitorAgent     │
 │   (premain / agentmain)  │                        │
 │                          │                        │
 │  ┌─────────────────────┐ │  HTTP GET /agent/*     │
 │  │   AgentHttpServer   │◄├───────────────────────┤
 │  │   (port 7979)       │ │                  [AgentHttpClient]
 │  │                     │ │                    - connect()
 │  │  /agent/threads     │ │                    - collect() 5s 주기
 │  │  /agent/jvm         │ │                    - jvmJson 캐시
 │  │  /agent/thread/{id} │ │                    - threadsJson 캐시
 │  │  /agent/threaddump  │ │                    - proxy 메서드들
 │  │  /agent/deadlocks   │─┼───────────────────►│
 │  │  /agent/requests    │ │   JSON / text 응답   │
 │  │  /agent/dbpools     │ │                      │
 │  │  /agent/top10       │ │                      │
 │  └─────────────────────┘ │              [MetricsHttpServer]
 │                          │                GET /agent/status
 │  ┌─────────────────────┐ │                GET /agent/jvm
 │  │ AgentThreadCollector│ │                GET /agent/threads
 │  │ (로컬 MXBean 수집)   │ │                GET /agent/thread/{id}
 │  │ - ThreadMXBean      │ │                GET /agent/threaddump
 │  │ - Catalina MBeans   │ │                GET /agent/deadlocks
 │  │ - Pool MBeans       │ │                GET /agent/requests
 │  └─────────────────────┘ │                GET /agent/dbpools
 │                          │                GET /agent/top10
 └──────────────────────────┘                      │
                                            [브라우저 대시보드]
                                              Agent 섹션 5s 갱신
```

---

## 5. 수집 메트릭 목록

### 로컬 JVM (JvmMetricsCollector)

| 카테고리 | 메트릭 | 설명 |
|----------|--------|------|
| **Heap** | `heap.used.mb` / `heap.committed.mb` / `heap.max.mb` | 힙 메모리 (MB) |
| | `heap.usage.percent` | 힙 사용률 (%) |
| | `nonheap.used.mb` / `metaspace.used.mb` | Non-Heap / Metaspace |
| **메모리 풀** | `pool.{name}.used.mb` | Eden, Survivor, Old Gen, Code Cache 등 |
| **GC** | `gc.total.count` / `gc.total.time.ms` | GC 전체 횟수/시간 |
| | `gcDetails()` → GC별 상세 | Young GC / Full GC 구분 |
| **스레드** | `thread.count` / `thread.daemon` / `thread.peak` | 스레드 수 |
| | `thread.blocked` / `thread.waiting` | BLOCKED / WAITING 상태 |
| | `findDeadlockedThreads()` | 데드락 감지 |
| **CPU** | `cpu.process` / `cpu.system` | 프로세스/시스템 CPU (%) |
| **OS 메모리** | `os.total.memory.mb` / `os.free.memory.mb` | 물리 메모리 |
| | `os.total.swap.mb` / `os.free.swap.mb` | 스왑 공간 |
| **Buffer Pool** | `buffer.direct.count` / `buffer.direct.mb` | Direct ByteBuffer |
| | `buffer.mapped.count` / `buffer.mapped.mb` | Mapped ByteBuffer |
| **JIT** | `jit.compile.time.ms` | JIT 총 컴파일 시간 |
| **런타임** | `uptime.ms` / `loaded.classes` / `jvm.name` | 기본 런타임 정보 |

### 트랜잭션 추적 (SpanStorage)

| 메트릭 | 설명 |
|--------|------|
| `totalSpans` | 누적 스팬 수 |
| `errorSpans` | 오류 스팬 수 |
| `avgDurationMs` | 평균 스팬 지속 시간 |
| `errorRate` | 오류율 (%) |

---

## 6. 설정 파일 (monitor.properties)

```
[적용 우선순위]
  낮음  ──────────────────────────────────────────  높음
  클래스패스 기본값 → 외부 파일(CWD) → -D 시스템 속성
```

| 설정 키 | 기본값 | 설명 |
|---------|--------|------|
| `server.http.port` | `9090` | HTTP 서버 포트 |
| `server.print.interval.sec` | `15` | 콘솔 JVM 스냅샷 출력 주기 |
| `server.span.buffer.size` | `1000` | 스팬 링 버퍼 크기 |
| `remote.jmx.enabled` | `false` | 원격 JMX 수집 활성화 |
| `remote.jmx.host` | `localhost` | 대상 JVM 호스트 |
| `remote.jmx.port` | `9999` | 대상 JVM JMX 포트 |
| `remote.jmx.user` / `remote.jmx.password` | (빈값) | JMX 인증 |
| `remote.jmx.poll.interval.sec` | `5` | 원격 수집 주기 |
| `remote.jmx.reconnect.interval.sec` | `30` | 재연결 주기 |
| `agent.enabled` | `false` | Agent HTTP 수집 활성화 |
| `agent.host` | `localhost` | Agent 대상 호스트 |
| `agent.port` | `7979` | Agent HTTP 포트 |
| `agent.poll.interval.sec` | `5` | Agent 수집 주기 |
| `alert.heap.percent` | `85` | Heap 경고 임계치 (%) |
| `alert.cpu.percent` | `80` | CPU 경고 임계치 (%) |
| `alert.error.rate.percent` | `10` | 오류율 경고 임계치 (%) |

---

## 7. HTTP API 엔드포인트

| 메서드 | 경로 | Content-Type | 설명 |
|--------|------|--------------|------|
| GET | `/dashboard` | `text/html` | 실시간 APM 대시보드 |
| GET | `/jvm` | `application/json` | 로컬 JVM 전체 메트릭 |
| GET | `/threads` | `application/json` | 로컬 스레드 목록 |
| GET | `/metrics` | `text/plain` | Prometheus scrape 포맷 |
| GET | `/traces` | `application/json` | 최근 스팬 목록 (50건) |
| GET | `/api/stats` | `application/json` | 스팬 통계 요약 |
| GET | `/api/config` | `application/json` | 대시보드 설정 (`tracesEnabled` 등) |
| GET | `/api/threaddump` | `text/plain` | 로컬 Thread Dump |
| GET | `/api/thread/{id}` | `application/json` | 로컬 단일 스레드 상세 + 전체 스택 |
| GET | `/health` | `application/json` | 헬스 체크 + 업타임 |
| GET | `/remote/jvm` | `application/json` | 원격 JVM 메트릭 스냅샷 |
| GET | `/remote/status` | `application/json` | 원격 JMX 연결 상태 |
| GET | `/remote/threads` | `application/json` | 원격 JVM 스레드 목록 |
| GET | `/remote/thread/{id}` | `application/json` | 원격 단일 스레드 전체 스택 |
| GET | `/remote/threaddump` | `text/plain` | 원격 Thread Dump (jstack 형식) |
| GET | `/remote/requests` | `application/json` | Tomcat HTTP 요청 현황 |
| GET | `/remote/dbpools` | `application/json` | DB 커넥션 풀 상태 |
| GET | `/agent/status` | `application/json` | Agent 연결 상태 |
| GET | `/agent/jvm` | `application/json` | Agent JVM 메트릭 (프록시) |
| GET | `/agent/threads` | `application/json` | Agent 스레드 목록 (프록시) |
| GET | `/agent/thread/{id}` | `application/json` | Agent 단일 스레드 상세 (프록시) |
| GET | `/agent/threaddump` | `text/plain` | Agent Thread Dump (프록시) |
| GET | `/agent/deadlocks` | `application/json` | Agent 데드락 감지 (프록시) |
| GET | `/agent/requests` | `application/json` | Agent HTTP 요청 현황 (프록시) |
| GET | `/agent/dbpools` | `application/json` | Agent DB 커넥션 풀 (프록시) |
| GET | `/agent/top10` | `application/json` | Agent CPU 처리시간 Top 10 스레드 + 전체 스택 (프록시) |

---

## 8. 패키지 구조

```
com.monosun.monitor
├── config/
│   └── MonitorConfig          설정 파일 로더 (monitor.properties)
├── core/
│   ├── JvmMetricsCollector    로컬 JVM MXBean 수집 + getThreadDetail()
│   ├── MonitoringAgent        에이전트 진입점 (Builder 패턴)
│   ├── Span                   트레이스 스팬 모델
│   ├── SpanStorage            링 버퍼 스팬 저장소
│   ├── TraceContext           ThreadLocal 트레이스 컨텍스트
│   └── TransactionTracer      스팬 추적 API (수동 / 람다)
├── remote/
│   ├── RemoteJvmCollector     JMX RMI 원격 수집 + 폴링
│   │                            getThreadDump(), getThreadDetail(id)
│   │                            getRequestProcessors(), getConnectionPools()
│   └── AgentHttpClient        Agent HTTP 폴링 클라이언트 + 프록시
├── agent/                     ← agent JAR (java-monitor-1.5.0-agent.jar)
│   ├── ThreadMonitorAgent     premain / agentmain 진입점
│   ├── AgentHttpServer        대상 JVM 내장 HTTP 서버 (port 7979)
│   └── AgentThreadCollector   로컬 MXBean 수집
│                                getThreadList(), getThreadDetail(id)
│                                getThreadDump(), getDeadlockInfo()
│                                getRequestProcessors(), getConnectionPools()
│                                getTopByProcessingTime(limit) — CPU 시간 기준 상위 N 스레드
├── server/
│   └── MetricsHttpServer      JDK 내장 HTTP 서버 (28개 엔드포인트)
├── exporter/
│   └── PrometheusExporter     Prometheus text/plain 변환
├── annotation/
│   └── @Trace                 메서드 자동 추적 어노테이션
├── integration/
│   ├── proxy/TracingProxy     인터페이스 기반 자동 @Trace 프록시
│   ├── servlet/MonitoringFilter  Servlet Filter 통합
│   └── jaxrs/MonitoringContainerFilter  JAX-RS 통합
└── demo/
    ├── MonitoringDemo         데모 실행 진입점
    └── service/               OrderService 데모 서비스
```

---

## 9. 연결 설정 예시

### 원격 JVM — JMX 모드

**대상 JVM 시작:**

```bash
java -jar myapp.jar \
  -Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.port=9999 \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false \
  -Djava.rmi.server.hostname=10.0.0.5
```

**monitor.properties:**

```properties
remote.jmx.enabled=true
remote.jmx.host=10.0.0.5
remote.jmx.port=9999
remote.jmx.poll.interval.sec=5
```

### 원격 JVM — Agent HTTP 모드

**대상 JVM 시작:**

```bash
java -javaagent:java-monitor-1.5.0-agent.jar=port=7979 -jar myapp.jar
```

**monitor.properties:**

```properties
agent.enabled=true
agent.host=10.0.0.5
agent.port=7979
agent.poll.interval.sec=5
```

### 대시보드 실행

```bash
# 원클릭 실행 (Windows)
bin\start.bat

# 백그라운드 실행
bin\startup.bat          # Windows
./bin/startup.sh         # Linux/macOS

# 직접 실행
java -jar java-monitor-1.12.1.jar
# 또는 설정 파일 직접 지정
java -jar java-monitor-1.12.1.jar /etc/myconfig/monitor.properties
```

대시보드: **http://localhost:9090/dashboard**
