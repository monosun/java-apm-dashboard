# Java APM Dashboard

Java 프로세스를 위한 **경량 APM(Application Performance Monitor)**.  
외부 라이브러리 없이 순수 JDK만으로 동작하며, 실시간 대시보드에서 JVM 상태, 스레드 분석, 트레이스, 원격 JVM 모니터링을 제공합니다.

---

## 주요 기능

| 영역 | 내용 |
|------|------|
| **JVM 메트릭** | Heap/Non-Heap, GC, CPU, Metaspace, Buffer Pool, JIT |
| **스레드 분석** | 상태별 분류(BLOCKED/WAITING/TIMED/RUNNABLE), CPU 시간, 스택 트레이스, 데드락 감지 |
| **트레이스** | Span 기반 트랜잭션 추적, 오류율, 평균 응답 시간 |
| **원격 JVM** | JMX RMI로 Tomcat / Spring Boot 등 외부 JVM 모니터링 |
| **Prometheus** | `/metrics` 엔드포인트 — Prometheus + Grafana 연동 |

---

## 빠른 시작

### 요구 사항
- JDK 21+
- Apache Maven 3.6+ (빌드 시)

### 1. 빌드 및 실행

```bat
:: Windows — JAR 없으면 자동 빌드 후 실행
start.bat

:: 명시적 빌드 후 실행
scripts\build.bat
scripts\run.bat 9090

:: 클린 빌드 후 즉시 실행
scripts\deploy.bat 9090
```

```bash
# Linux / macOS
chmod +x scripts/run.sh
./scripts/run.sh
```

### 2. 대시보드 접속

| URL | 설명 |
|-----|------|
| `http://localhost:9090/dashboard` | 실시간 APM 대시보드 |
| `http://localhost:9090/metrics`   | Prometheus 형식 메트릭 |
| `http://localhost:9090/jvm`       | JVM 스냅샷 JSON |
| `http://localhost:9090/threads`   | 로컬 스레드 목록 JSON |
| `http://localhost:9090/api/threaddump` | Full Thread Dump (text) |
| `http://localhost:9090/traces`    | 최근 스팬 JSON |
| `http://localhost:9090/health`    | 헬스 체크 |

---

## 대시보드 구조

```
┌─────────────────────────────────────────────────┐
│  HEADER  상태 표시 / 업타임 / JVM 이름            │
├─────────────────────────────────────────────────┤
│  [상단] APM 에이전트 상태                          │
│   • Heap / CPU / 스레드 상태 / GC / 스팬 오류율   │
│   • Heap / CPU / 스레드 상태 / GC 히스토리 차트   │
│   • 로컬 JVM 스레드 테이블 (상태, CPU, 스택)       │
├─────────────────────────────────────────────────┤
│  [하단] 모니터링 대상                              │
│   • 원격 JVM (Tomcat / Spring Boot)              │
│     - Heap / CPU / 스레드 / GC 카드               │
│     - Heap / CPU 히스토리 차트                    │
│     - 대상 스레드 테이블                           │
│   • 최근 트레이스 (스팬) 테이블                    │
└─────────────────────────────────────────────────┘
```

---

## 원격 JVM 연동

`monitor.properties`에서 연결 대상을 설정합니다:

```properties
remote.jmx.enabled=true
remote.jmx.host=192.168.1.100
remote.jmx.port=9999
remote.jmx.poll.interval.sec=5
```

대상 JVM 시작 시 JMX Remote 옵션 추가:

```bash
# Spring Boot
java -Dcom.sun.management.jmxremote \
     -Dcom.sun.management.jmxremote.port=9999 \
     -Dcom.sun.management.jmxremote.authenticate=false \
     -Dcom.sun.management.jmxremote.ssl=false \
     -Djava.rmi.server.hostname=<서버_IP> \
     -jar myapp.jar

# Tomcat — bin/setenv.sh 에 CATALINA_OPTS 추가
export CATALINA_OPTS="$CATALINA_OPTS -Dcom.sun.management.jmxremote ..."
```

**Tomcat / Spring Boot 상세 설정**: [`docs/integration-guide.md`](docs/integration-guide.md)

---

## 코드에서 트레이스 추가

```java
// 에이전트 초기화
MonitoringAgent agent = MonitoringAgent.builder()
    .httpPort(9090)
    .build()
    .start();

TransactionTracer tracer = agent.tracer();

// 람다 기반 트레이스
String result = tracer.trace("user.fetch", () -> userService.findById(id));

// 수동 Span
TraceContext ctx = TraceContext.startTrace();
Span span = ctx.startSpan("payment.process");
span.tag("method", "card");
try {
    ctx.finishSpan(span);
} catch (Exception e) {
    ctx.finishSpan(span, e);
} finally {
    TraceContext.clear();
}
```

---

## 설정 파일 (`monitor.properties`)

```properties
server.http.port=9090
server.print.interval.sec=15
server.span.buffer.size=1000

remote.jmx.enabled=false
remote.jmx.host=localhost
remote.jmx.port=9999
remote.jmx.user=
remote.jmx.password=
remote.jmx.poll.interval.sec=5
remote.jmx.reconnect.interval.sec=30

alert.heap.percent=85
alert.cpu.percent=80
alert.error.rate.percent=10
```

시스템 속성으로 재정의:
```bash
java -Dserver.http.port=8080 -Dremote.jmx.enabled=true -jar java-monitor.jar
```

---

## HTTP API

| Method | Path | 설명 |
|--------|------|------|
| GET | `/dashboard` | 실시간 APM 대시보드 (HTML) |
| GET | `/metrics` | Prometheus 텍스트 형식 |
| GET | `/health` | 헬스 체크 JSON |
| GET | `/jvm` | 로컬 JVM 메트릭 JSON |
| GET | `/threads` | 로컬 스레드 상세 목록 JSON |
| GET | `/api/threaddump` | Full Thread Dump (text/plain) |
| GET | `/traces` | 최근 스팬 JSON |
| GET | `/api/stats` | 스팬 통계 JSON |
| GET | `/remote/jvm` | 원격 JVM 메트릭 JSON |
| GET | `/remote/threads` | 원격 JVM 스레드 목록 JSON |
| GET | `/remote/status` | 원격 JVM 연결 상태 JSON |

---

## 문서

- [통합 연동 가이드 (Tomcat / Spring Boot)](docs/integration-guide.md)
- [설정 레퍼런스](docs/configuration.md)
- [아키텍처](docs/architecture.md)

---

## 라이선스

MIT License
