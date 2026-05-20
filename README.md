# Java APM Dashboard v1.5.0

Java 프로세스를 위한 **경량 APM(Application Performance Monitor)**.  
외부 라이브러리 없이 순수 JDK만으로 동작하며, 실시간 대시보드에서 JVM 상태, 스레드 심층 분석, Thread Dump, DB 커넥션 풀, HTTP 요청 처리 현황, CPU 처리시간 Top 10을 제공합니다.

---

## 주요 기능

| 영역 | 내용 |
|------|------|
| **JVM 메트릭** | Heap/Non-Heap, GC, CPU, Metaspace, Buffer Pool, JIT |
| **스레드 심층 분석** | 상태 필터/이름 검색, 전체 스택 트레이스 모달, 실시간 Live Stack 뷰, Thread Dump 뷰어(다운로드), 데드락 감지 |
| **CPU 처리시간 Top 10** | Agent 연결 시 CPU 누적 시간 상위 10 스레드 — 전체 스택 트레이스 인라인 표시 |
| **HTTP 요청 처리** | Tomcat/Spring Boot 스레드별 처리 시간, URI, 누적 오류 수 |
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
- `target/java-monitor-1.5.0.jar` — 메인 APM 대시보드 서버
- `target/java-monitor-1.5.0-agent.jar` — 대상 JVM 부착용 Agent Library

### 2. 실행

```bat
:: Windows 원클릭
start.bat

:: 명시적 실행
scripts\run.bat 9090

:: 클린 빌드 + 실행
scripts\deploy.bat 9090
```

```bash
# Linux / macOS
./scripts/run.sh
```

### 3. 대시보드 접속

```
http://localhost:9090/dashboard
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
│   • 활성 HTTP 요청 테이블 (Tomcat RequestProcessor)          │
├──────────────────────────────────────────────────────────────┤
│  [3] Agent 연결 대상 (HTTP Agent)                             │
│   • ThreadMonitorAgent 부착 JVM 상태 카드                    │
│   • 스레드 테이블 + Live Stack 패널                          │
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
java -javaagent:java-monitor-1.5.0-agent.jar=port=7979 -jar your-app.jar
```

### 대상 JVM에 동적 부착 (Attach API)

```java
import com.sun.tools.attach.VirtualMachine;

VirtualMachine vm = VirtualMachine.attach("PID");
vm.loadAgent("/path/to/java-monitor-1.5.0-agent.jar", "port=7979");
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

## 릴리즈 노트

### v1.5.0
- **CPU 처리시간 Top 10**: Agent 연결 시 CPU 누적 시간 기준 상위 10 스레드 자동 표시
  - 경량 1차 조회로 상위 N 선별 후 전체 스택만 적재 (성능 최적화)
  - 행 클릭 시 CPU/User/Blocked/Waited 시간 + 전체 스택 트레이스 인라인 펼치기
  - `GET /agent/top10` 엔드포인트 추가 (Agent 서버 + 대시보드 프록시)
- **트레이스 섹션 제거**: 대시보드에서 TracingProxy 기반 최근 트레이스(Span) 섹션 제거
  - `/traces` API 엔드포인트와 `SpanStorage` 내부 수집은 유지됨

### v1.4.0
- **Agent Library** 추가: `-javaagent:java-monitor-1.5.0-agent.jar=port=7979`
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
