# Java APM Dashboard v1.8.0

Java 프로세스를 위한 **경량 APM(Application Performance Monitor)**.  
외부 라이브러리 없이 순수 JDK만으로 동작하며, 실시간 대시보드에서 JVM 상태, 스레드 심층 분석, Thread Dump, DB 커넥션 풀, HTTP 요청 처리 현황 (스레드명·개별/평균 처리시간), CPU 처리시간 Top 10, HTTP 요청 로그 파일을 제공합니다.

---

## 주요 기능

| 영역 | 내용 |
|------|------|
| **JVM 메트릭** | Heap/Non-Heap, GC, CPU, Metaspace, Buffer Pool, JIT |
| **스레드 심층 분석** | 상태 필터/이름 검색, 전체 스택 트레이스 모달, 실시간 Live Stack 뷰, Thread Dump 뷰어(다운로드), 데드락 감지 |
| **CPU 처리시간 Top 10** | Agent 연결 시 CPU 누적 시간 상위 10 스레드 — 전체 스택 트레이스 인라인 표시 |
| **HTTP 요청 처리** | Tomcat RequestProcessor — Method·URI·Remote IP·**스레드명**·**개별 처리시간**·**평균 처리시간** 표시, 행 클릭 시 스레드 상세·스택 트레이스 모달, 요청 내역 로그파일(`logs/http-requests.log`) 자동 기록 |
| **DB 커넥션 풀** | HikariCP, Tomcat JDBC, DBCP2 자동 감지 — Active/Idle/Max/대기 스레드 |
| **원격 JVM (JMX)** | JMX RMI로 Tomcat / Spring Boot 등 외부 JVM 모니터링 |
| **Agent Library** | `-javaagent:` 부착으로 JMX 없이 HTTP 기반 스레드 모니터링 |
| **Prometheus** | `/metrics` 엔드포인트 — Prometheus + Grafana 연동 |

---

## 빠른 시작

### 요구 사항
- JDK 21+
- Apache Maven 3.6+ (빌드 시)

### 1. 빌드

```bat
scripts\build.bat
:: 또는
mvn clean package -DskipTests
```

빌드 결과:
- `target/java-monitor-1.8.0.jar` — 메인 APM 대시보드 서버
- `target/java-monitor-1.8.0-agent.jar` — 대상 JVM 부착용 Agent Library

### 2. 실행

```bat
:: Windows 원클릭 (포그라운드)
start.bat

:: 백그라운드 시작 / 종료
scripts\startup.bat
scripts\shutdown.bat
```

```bash
# Linux / macOS 백그라운드 시작 / 종료
./scripts/startup.sh
./scripts/shutdown.sh
```

### 3. 대시보드 접속

```
http://localhost:9090/dashboard
```

---

## scripts/ 디렉토리

```
scripts/
├── startup.bat      Windows  — 백그라운드 시작, PID 파일 저장, 브라우저 자동 오픈
├── shutdown.bat     Windows  — PID 파일·wmic·netstat 3단계 탐색 후 종료
├── startup.sh       Linux/macOS — nohup 백그라운드 시작, 헬스 체크 대기
├── shutdown.sh      Linux/macOS — SIGTERM → 15초 대기 → SIGKILL graceful 종료
├── build.bat        Maven 빌드 (clean 옵션 포함)
├── run.bat          포그라운드 실행 (빌드 + 실행 / 실행만 선택)
├── run.sh           포그라운드 실행 (Linux/macOS)
├── deploy.bat       클린 빌드 후 즉시 실행
└── logging.properties  JUL 로그 설정 (콘솔 + 파일 로테이션)
```

### startup / shutdown 공통 특징

| 항목 | 내용 |
|------|------|
| PID 파일 | `logs/monitor.pid` — 두 스크립트가 공유해 정확한 프로세스 추적 |
| 이중 기동 방지 | PID 파일 + 프로세스 존재 여부 이중 확인 |
| 헬스 체크 | 시작 후 최대 30초 `/health` 폴링으로 실제 기동 확인 |
| 로그 | `logs/monitor.log`, `logs/gc.log`, `logs/heapdump.hprof` |

### Windows 사용법

```bat
:: 백그라운드로 서버 시작 (최소화 창, 브라우저 자동 오픈)
scripts\startup.bat

:: 서버 종료
scripts\shutdown.bat
```

`startup.bat` 단계:

1. `logs\monitor.pid` 확인 → 이미 실행 중이면 종료
2. `target\` 에서 최신 JAR 자동 탐색 (`original` / `agent` 제외)
3. 최소화 독립 창(`start /MIN`)으로 JVM 시작
4. `wmic` 으로 PID 확인 후 `logs\monitor.pid` 저장
5. 헬스 체크 통과 시 브라우저 자동 오픈

`shutdown.bat` 단계:

1. `/health` 응답 없으면 즉시 종료
2. PID 파일 → `wmic` → `netstat` 3단계로 PID 탐색
3. `taskkill /F` 후 PID 파일 삭제

### Linux / macOS 사용법

```bash
# 실행 권한 부여 (최초 1회)
chmod +x scripts/startup.sh scripts/shutdown.sh

# 백그라운드로 서버 시작
./scripts/startup.sh

# 포트 지정 시작
./scripts/startup.sh 8080

# 서버 종료
./scripts/shutdown.sh
```

`startup.sh` 단계:

1. PID 파일 + `kill -0` 으로 이중 기동 확인
2. `target/` 에서 최신 JAR 탐색
3. `nohup ... >> logs/monitor.log 2>&1 &` 로 백그라운드 시작
4. PID 를 `logs/monitor.pid` 에 저장
5. 30초 헬스 체크 루프 통과 시 `xdg-open` / `open` 으로 브라우저 오픈

`shutdown.sh` 단계:

1. `/health` 응답 없으면 즉시 종료
2. PID 파일 → `pgrep` → `fuser` 3단계로 PID 탐색
3. `SIGTERM` 후 15초 대기 → 미종료 시 `SIGKILL`
4. PID 파일 삭제

### 로그 확인

```bash
# 실시간 로그 확인 (Linux/macOS)
tail -f logs/monitor.log

# GC 로그
tail -f logs/gc.log
```

```bat
:: Windows
type logs\monitor.log
```

---

## 대시보드 구조

```
┌──────────────────────────────────────────────────────────────┐
│  HEADER  상태 / 업타임 / JVM 이름                             │
├──────────────────────────────────────────────────────────────┤
│  [1] APM 에이전트 상태                                        │
│   • Heap / CPU / 스레드 상태 / GC / Span 통계 카드           │
│   • 히스토리 차트 (Heap, CPU, 스레드 상태, GC)               │
│   • 스레드 테이블 (상태 필터 / 이름 검색 / 행 클릭 → 상세)   │
│     - 행 클릭: 전체 스택 트레이스, CPU/WAIT 시간, 잠금 정보  │
│   • Thread Dump 버튼 → 모달 뷰어 (필터, 복사, 다운로드)      │
│   • Live Stack 트레이스 패널 (접기/펼치기)                    │
├──────────────────────────────────────────────────────────────┤
│  [2] 모니터링 대상 (JMX)                                      │
│   • 원격 JVM 연결 상태 / 메트릭 카드 / 히스토리 차트         │
│   • 스레드 테이블 + Live Stack 패널                          │
│     - Thread Dump 버튼 옆 갱신 주기 선택(1/3/5/10/30초)     │
│       + ⟳ 즉시 갱신 버튼                                    │
│     - Live Stack: HTTP 워커 스레드 이름 강조(하늘색)         │
│       + 🌐 URI ← RemoteIP · 개별처리시간ms 배지              │
│       + 패널 상단에 활성요청수·최대·평균 처리시간 요약 표시  │
│   • 활성 HTTP 요청 테이블 (Tomcat RequestProcessor)          │
│     - Method 배지 / URI / Remote IP / 스레드 이름            │
│     - 개별 처리시간 + 평균 처리시간(ms) 컬럼                 │
│     - 테이블 상단에 활성 N건 · 평균 Xms 요약                 │
│     - 행 클릭 → 요청 상세 모달                               │
│       (요청 정보, 처리 통계, 스레드 상태 + 전체 스택 트레이스)│
├──────────────────────────────────────────────────────────────┤
│  [3] Agent 연결 대상 (HTTP Agent)  ← JMX와 동일 기능 제공   │
│   • ThreadMonitorAgent 부착 JVM 상태 카드                    │
│   • 스레드 테이블 + Live Stack 패널 (HTTP 요청 연동)         │
│     - 스레드 이름 강조 · 처리시간 배지 · 패널 상단 요약      │
│   • CPU 처리시간 Top 10 스레드                               │
│   • 활성 HTTP 요청 테이블 (Agent RequestProcessor)           │
│     - 스레드 이름 / 개별 처리시간 / 평균 처리시간            │
│   • Thread Dump 버튼                                         │
├──────────────────────────────────────────────────────────────┤
│  [4] DB Connection Pool                                       │
│   • HikariCP / Tomcat JDBC / DBCP2 자동 감지                 │
│   • Active / Idle / Total / Max / 대기 스레드                │
├──────────────────────────────────────────────────────────────┤
│  [5] 최근 트레이스 (server.traces.enabled=true 시)           │
│   • Span 테이블 (Operation, Duration, Status, Trace ID)      │
└──────────────────────────────────────────────────────────────┘
```

---

## Agent Library 사용법

JMX 포트 없이 순수 HTTP 방식으로 대상 JVM의 스레드를 모니터링합니다.

### 대상 JVM에 부착 (정적)

```bash
java -javaagent:java-monitor-1.8.0-agent.jar=port=7979 -jar your-app.jar
```

### 대상 JVM에 동적 부착 (Attach API)

```java
import com.sun.tools.attach.VirtualMachine;

VirtualMachine vm = VirtualMachine.attach("PID");
vm.loadAgent("/path/to/java-monitor-1.6.0-agent.jar", "port=7979");
vm.detach();
```

### 대시보드 서버에서 연결 (`monitor.properties`)

```properties
agent.enabled=true
agent.host=target-host-or-ip
agent.port=7979
agent.poll.interval.sec=5
```

### Agent 노출 엔드포인트

| URL | 설명 |
|-----|------|
| `http://target:7979/agent/threads`     | 스레드 목록 JSON |
| `http://target:7979/agent/thread/{id}` | 단일 스레드 전체 스택 JSON |
| `http://target:7979/agent/threaddump`  | 전체 Thread Dump (text) |
| `http://target:7979/agent/jvm`         | JVM 메트릭 JSON |
| `http://target:7979/agent/deadlocks`   | 데드락 감지 JSON |
| `http://target:7979/agent/requests`    | Tomcat HTTP 요청 현황 JSON |
| `http://target:7979/agent/dbpools`     | DB 커넥션 풀 상태 JSON |
| `http://target:7979/agent/top10`       | CPU 처리시간 Top 10 스레드 + 전체 스택 JSON |
| `http://target:7979/agent/health`      | 헬스 체크 |

---

## 원격 JVM 연동 (JMX)

```properties
# monitor.properties
remote.jmx.enabled=true
remote.jmx.host=192.168.1.100
remote.jmx.port=9999
remote.jmx.poll.interval.sec=5
```

대상 JVM 시작 옵션:

```bash
java -Dcom.sun.management.jmxremote \
     -Dcom.sun.management.jmxremote.port=9999 \
     -Dcom.sun.management.jmxremote.authenticate=false \
     -Dcom.sun.management.jmxremote.ssl=false \
     -Djava.rmi.server.hostname=<서버_IP> \
     -jar myapp.jar
```

---

## DB 커넥션 풀 MBean 활성화

### HikariCP (Spring Boot)

```yaml
# application.yml
spring:
  datasource:
    hikari:
      register-mbeans: true
```

### Tomcat JDBC Pool

```xml
<!-- context.xml 또는 DataSource 설정 -->
<Resource ... jmxEnabled="true" />
```

---

## HTTP API 전체 목록

| Method | Path | 설명 |
|--------|------|------|
| GET | `/dashboard` | 실시간 APM 대시보드 |
| GET | `/metrics` | Prometheus 텍스트 형식 |
| GET | `/health` | 헬스 체크 JSON |
| GET | `/jvm` | 로컬 JVM 메트릭 JSON |
| GET | `/threads` | 로컬 스레드 목록 JSON |
| GET | `/api/threaddump` | 로컬 Thread Dump (text/plain) |
| GET | `/api/thread/{id}` | 로컬 단일 스레드 상세 + 전체 스택 JSON |
| GET | `/api/stats` | Span 통계 JSON |
| GET | `/api/config` | 대시보드 설정 JSON |
| GET | `/traces` | 최근 Span JSON |
| GET | `/remote/jvm` | 원격 JVM 메트릭 JSON (JMX) |
| GET | `/remote/threads` | 원격 스레드 목록 JSON |
| GET | `/remote/thread/{id}` | 원격 단일 스레드 상세 JSON |
| GET | `/remote/threaddump` | 원격 Thread Dump (text/plain) |
| GET | `/remote/requests` | Tomcat HTTP 요청 현황 JSON |
| GET | `/remote/dbpools` | DB 커넥션 풀 상태 JSON |
| GET | `/remote/status` | 원격 JVM 연결 상태 JSON |
| GET | `/agent/status` | Agent 연결 상태 JSON |
| GET | `/agent/jvm` | Agent JVM 메트릭 (프록시) |
| GET | `/agent/threads` | Agent 스레드 목록 (프록시) |
| GET | `/agent/thread/{id}` | Agent 단일 스레드 상세 (프록시) |
| GET | `/agent/threaddump` | Agent Thread Dump (프록시) |
| GET | `/agent/deadlocks` | Agent 데드락 감지 (프록시) |
| GET | `/agent/requests` | Agent HTTP 요청 현황 (프록시) |
| GET | `/agent/dbpools` | Agent DB 커넥션 풀 (프록시) |
| GET | `/agent/top10` | Agent CPU 처리시간 Top 10 스레드 + 전체 스택 (프록시) |

---

## 설정 파일 (`monitor.properties`)

```properties
# ── HTTP 서버 ──────────────────────────────────────────────────
server.http.port=9090
server.print.interval.sec=15
server.span.buffer.size=1000

# ── 원격 JVM 모니터링 (JMX) ───────────────────────────────────
remote.jmx.enabled=false
remote.jmx.host=localhost
remote.jmx.port=9999
remote.jmx.user=
remote.jmx.password=
remote.jmx.poll.interval.sec=5
remote.jmx.reconnect.interval.sec=30

# ── Agent HTTP 모니터링 ───────────────────────────────────────
agent.enabled=false
agent.host=localhost
agent.port=7979
agent.poll.interval.sec=5

# ── 경고 임계치 ────────────────────────────────────────────────
alert.heap.percent=85
alert.cpu.percent=80
alert.error.rate.percent=10
```

시스템 속성으로 재정의:

```bash
java -Dserver.http.port=8080 -Dagent.enabled=true -Dagent.host=myapp -jar java-monitor.jar
```

---

## 코드에서 트레이스 추가

```java
MonitoringAgent agent = MonitoringAgent.builder()
    .httpPort(9090)
    .build()
    .start();

TransactionTracer tracer = agent.tracer();

// 람다 기반
String result = tracer.trace("user.fetch", () -> userService.findById(id));

// 수동 Span
TraceContext ctx = TraceContext.startTrace();
Span span = ctx.startSpan("payment.process");
span.tag("method", "card");
try {
    // ...
    ctx.finishSpan(span);
} catch (Exception e) {
    ctx.finishSpan(span, e);
} finally {
    TraceContext.clear();
}
```

---

## 문서

- [통합 연동 가이드 (Tomcat / Spring Boot)](docs/integration-guide.md)
- [설정 레퍼런스](docs/configuration.md)
- [아키텍처](docs/architecture.md)

---

## HTTP 요청 로그 파일

v1.8.0부터 활성 HTTP 요청이 감지될 때마다 `logs/http-requests.log`에 자동 기록됩니다.

**로그 형식** (파이프 구분, 한 줄 = 요청 1건):

```
2026-05-24T05:30:12.123Z | src=jmx | thread=http-nio-8080-exec-3 | method=POST | uri=/api/order?id=42 | ip=192.168.1.10 | vhost=localhost | ct=application/json | stage=3 | procMs=142 | reqCount=1024 | errors=0 | bytesSent=512 | bytesRecv=256
```

| 필드 | 설명 |
|------|------|
| `src` | 수집 경로 (`jmx` 또는 `agent`) |
| `thread` | 처리 스레드 이름 (Tomcat: `http-nio-{port}-exec-{N}`) |
| `method` | HTTP Method (GET, POST, …) |
| `uri` | 요청 경로 + 쿼리스트링 |
| `ip` | Remote IP |
| `vhost` | Tomcat Virtual Host |
| `ct` | Content-Type |
| `stage` | Tomcat 처리 단계 (3=SERVICE 활성) |
| `procMs` | 현재 요청 처리 시간 (ms) |
| `reqCount` | 해당 워커의 누적 요청 수 |
| `errors` | 누적 오류 수 |
| `bytesSent/Recv` | 전송·수신 바이트 |

> HTTP 요청 헤더(Accept, Authorization 등)는 Tomcat RequestProcessor MBean이 노출하지 않으므로 포함되지 않습니다.

---

## 릴리즈 노트

### v1.8.0
- **Live Stack 뷰 HTTP 요청 강화 (JMX + Agent 공통)**
  - HTTP 요청 처리 중인 스레드 이름을 하늘색으로 강조 표시
  - reqBadge에 개별 처리시간(ms) 추가, 5초 초과 시 빨간색 경고
  - 패널 상단에 활성 HTTP 요청 수 · 최대 처리시간 · 평균 처리시간 요약 표시
- **HTTP 요청 테이블 개선 (JMX + Agent 공통)**
  - `스레드 이름` 컬럼 헤더 명확화 (기존 "스레드" → "스레드 이름")
  - **평균 처리시간(ms) 컬럼** 추가 — 활성 요청 전체 평균 계산
  - 테이블 상단 요약: "활성 N건 · 평균 Xms"
- **Agent 섹션 기능 확장 (JMX와 동일 수준)**
  - Agent 섹션에 **HTTP 요청 테이블** 추가 (`agent-requests-tbody`)
  - Agent Live Stack 뷰에서도 HTTP 워커 강조·처리시간·요약 표시
- **HTTP 요청 로그 파일** (`logs/http-requests.log`)
  - JMX와 Agent 양쪽에서 신규 활성 요청 감지 시 자동 기록
  - 중복 기록 방지: 동일 worker+requestCount는 한 번만 기록
  - 파이프 구분 포맷: timestamp | src | thread | method | uri | ip | vhost | ct | stage | procMs | reqCount | errors | bytesSent | bytesRecv

### v1.7.0
- **HTTP 요청 상세 모달**: 활성 HTTP 요청 테이블 행 클릭 시 상세 정보 표시
  - 요청 정보: HTTP Method 배지, URI, 쿼리스트링, Virtual Host, Remote IP, Stage(인간 친화적 레이블), Content-Type
  - 처리 통계: 처리 시간, 누적 요청수·오류수, 전송/수신 Bytes
  - 처리 스레드: 스레드 상태·이름, CPU 시간, 잠금 정보, 전체 스택 트레이스 (JMX 실시간 조회)
- **원격 JVM 갱신 컨트롤**: 대상 스레드 Thread Dump 버튼 옆에 추가
  - 갱신 주기 선택 (1초 / 3초 / 5초 / 10초 / 30초)
  - ⟳ 즉시 갱신 버튼 — 스레드 테이블 + HTTP 요청 테이블 동시 갱신
- **스레드 이름 수정**: HTTP 요청 테이블에서 idle 워커 스레드명이 `?`로 표시되던 문제 수정
  - `currentThreadName` 빈 값 시 ObjectName `name` 키로 폴백
- **Remote IP 정규화**: `0:0:0:0:0:0:0:1` / `::1` → `127.0.0.1` 자동 변환 (JMX + Agent 양쪽)
- **Method 컬럼 추가**: HTTP 요청 테이블에 HTTP Method(GET/POST 등) 컬럼 추가
- **추가 수집 필드**: `method`, `queryString`, `contentType`, `virtualHost`, `bytesReceived` (Tomcat RequestProcessor MBean)

### v1.6.0
- **Live Stack Trace — HTTP 요청 연동**: 실시간 스택 트레이스 뷰에서 Tomcat RequestProcessor와 스레드명을 자동 매칭
  - HTTP 워커 스레드 헤더에 `🌐 /api/path ← RemoteIP` 배지 실시간 표시
  - JMX Remote 연결(원격 JVM)과 Agent HTTP 연결 양쪽 모두 지원
  - `requestData` 캐시 분리로 스택 뷰 렌더링과 HTTP 폴링이 독립 동작 (3초 갱신)
  - URI가 없는 idle 스레드는 배지를 표시하지 않아 노이즈 제거

### v1.5.0
- **CPU 처리시간 Top 10**: Agent 연결 시 CPU 누적 시간 기준 상위 10 스레드 자동 표시
  - 경량 1차 조회로 상위 N 선별 후 전체 스택만 적재 (성능 최적화)
  - 행 클릭 시 CPU/User/Blocked/Waited 시간 + 전체 스택 트레이스 인라인 펼치기
  - `GET /agent/top10` 엔드포인트 추가 (Agent 서버 + 대시보드 프록시)
- **트레이스 섹션 제거**: 대시보드에서 TracingProxy 기반 최근 트레이스(Span) 섹션 제거
  - `/traces` API 엔드포인트와 `SpanStorage` 내부 수집은 유지됨

### v1.4.0
- **Agent Library** 추가: `-javaagent:java-monitor-1.6.0-agent.jar=port=7979`
- **스레드 심층 분석**: 상태 필터, 이름 검색, 전체 스택 트레이스 모달, CPU/대기 시간
- **Live Stack Trace 뷰**: 실시간 스택 트레이스 패널 (접기/펼치기)
- **Thread Dump 뷰어**: 모달 내 구문 강조, 필터, 복사, 다운로드
- **HTTP 요청 현황**: Tomcat RequestProcessor 스레드별 처리 시간, URI
- **DB 커넥션 풀**: HikariCP / Tomcat JDBC / DBCP2 자동 감지
- **원격 Thread Dump**: JMX 대상 JVM Thread Dump (text/plain)
- `server.traces.enabled` 설정으로 트레이스 섹션 on/off

### v1.3.0
- 스레드 분석 강화 (BLOCKED/WAITING 상태 추이 차트)
- 대시보드 레이아웃 재설계

---

## 라이선스

MIT License
