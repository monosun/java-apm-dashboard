# Java APM Dashboard — 설정 매뉴얼

## 목차

1. [설정 파일 위치 및 우선순위](#1-설정-파일-위치-및-우선순위)
2. [서버 설정](#2-서버-설정-server)
3. [Agent HTTP 설정](#3-agent-http-설정-agent)
4. [경고 임계치 설정](#4-경고-임계치-설정-alert)
5. [JVM 실행 옵션](#5-jvm-실행-옵션)
6. [로그 설정](#6-로그-설정)
7. [시스템 속성으로 덮어쓰기](#7-시스템-속성으로-덮어쓰기--d)
8. [환경별 설정 예시](#8-환경별-설정-예시)
9. [트러블슈팅](#9-트러블슈팅)

> **v1.11.0 변경**: Remote JMX 모니터링이 제거되었습니다. `remote.jmx.*` 설정은 더 이상 지원되지 않으며, Agent HTTP 방식(`agent.*`)으로 대체됩니다.

> **v1.5.0 변경**: `server.traces.enabled` 설정이 제거되었습니다. v1.5.0부터 대시보드의 트레이스 섹션은 표시되지 않으며, `/traces` API 및 `SpanStorage` 내부 수집은 계속 동작합니다.

---

## 1. 설정 파일 위치 및 우선순위

### 파일 위치

| 위치 | 설명 |
|------|------|
| JAR 내부 (`classpath:monitor.properties`) | 기본값. JAR에 포함되어 있음 |
| JAR와 같은 디렉터리의 `monitor.properties` | 외부 파일. 기본값을 덮어씀 |
| 실행 시 인수 | `java -jar ... /path/to/myconfig.properties` |

### 적용 우선순위

```
낮음 ──────────────────────────────────────── 높음
  JAR 내 기본값  →  외부 파일  →  -D 시스템 속성
```

우선순위가 높은 설정이 낮은 설정을 **덮어씁니다**. 외부 파일에 지정하지 않은 항목은 기본값이 그대로 사용됩니다.

### 외부 설정 파일 만들기

JAR 파일과 같은 디렉터리에 `monitor.properties` 파일을 만들면 자동으로 적용됩니다.

```
/apps/java-monitor/
├── bin/
│   ├── start.bat / startup.sh   ← 실행 스크립트
│   ├── shutdown.bat / shutdown.sh
│   ├── monitor.properties       ← 이 파일을 수정하면 자동 적용됨
│   └── logging.properties
├── target/
│   └── java-monitor-1.12.2.jar
└── logs/
```

또는 실행 시 경로를 직접 지정할 수 있습니다.

```bash
java -jar java-monitor-1.6.0.jar /etc/monitor/prod.properties
```

---

## 2. 서버 설정 (`server.*`)

### `server.http.port`

| 항목 | 값 |
|------|----|
| 기본값 | `9090` |
| 유형 | 정수 (1024 ~ 65535) |

HTTP 메트릭 서버의 포트입니다. 이 포트로 대시보드, API, Prometheus scrape 요청이 모두 들어옵니다.

```properties
server.http.port=9090
```

**포트 충돌 시** 다른 포트로 변경합니다.

```properties
server.http.port=18080
```

접속 URL도 함께 바뀝니다.
```
http://localhost:18080/dashboard
http://localhost:18080/metrics
```

---

### `server.print.interval.sec`

| 항목 | 값 |
|------|----|
| 기본값 | `15` |
| 유형 | 정수 (초), `0` = 비활성 |

JVM 스냅샷을 콘솔(표준 출력)에 주기적으로 출력하는 간격입니다.

```properties
# 30초마다 콘솔에 JVM 상태 출력
server.print.interval.sec=30

# 콘솔 출력 비활성화
server.print.interval.sec=0
```

출력 예시:
```
===== JVM Snapshot =====
  heap.used.mb             128
  heap.max.mb              512
  heap.usage.percent       25.0%
  thread.count             23
  cpu.process              3.2%
  ...
========================
```

---

### `server.span.buffer.size`

| 항목 | 값 |
|------|----|
| 기본값 | `1000` |
| 유형 | 정수 |

트랜잭션 스팬을 보관하는 링 버퍼의 크기입니다. 버퍼가 가득 차면 가장 오래된 스팬부터 제거됩니다.

```properties
# 최근 5000건 스팬 보관
server.span.buffer.size=5000
```

> **메모리 영향**: 스팬 1건당 약 1~2KB. 기본값 1000건은 약 1~2MB 사용.

---

## 3. Agent HTTP 설정 (`agent.*`)

JMX 없이 순수 HTTP 방식으로 대상 JVM의 스레드 정보를 수집합니다.  
대상 JVM에 `-javaagent:java-monitor-1.6.0-agent.jar=port=7979`를 부착한 뒤 이 설정을 활성화합니다.

### `agent.enabled`

| 항목 | 값 |
|------|----|
| 기본값 | `false` |
| 유형 | `true` / `false` |

Agent HTTP 수집 기능을 켜고 끕니다.

```properties
agent.enabled=true
```

---

### `agent.host`

| 항목 | 값 |
|------|----|
| 기본값 | `localhost` |
| 유형 | 호스트명 또는 IP |

ThreadMonitorAgent가 실행 중인 대상 JVM의 주소입니다.

```properties
agent.host=10.0.0.5
```

---

### `agent.port`

| 항목 | 값 |
|------|----|
| 기본값 | `7979` |
| 유형 | 정수 (1024 ~ 65535) |

ThreadMonitorAgent HTTP 서버의 포트입니다. Agent 부착 시 지정한 `port=` 값과 일치해야 합니다.

```properties
agent.port=7979
```

---

### `agent.poll.interval.sec`

| 항목 | 값 |
|------|----|
| 기본값 | `5` |
| 유형 | 정수 (초) |

Agent에서 스레드 목록과 JVM 메트릭을 수집하는 주기입니다.

```properties
agent.poll.interval.sec=3
```

---

### Agent 연결 확인

```bash
curl http://localhost:9090/agent/status
# {"connected":true,"target":"localhost:7979","lastCollectedMs":1230,"error":""}
```

---

## 4. 경고 임계치 설정 (`alert.*`)

임계치를 초과하면 콘솔에 `WARN` 레벨 로그를 출력합니다.

> **현재 버전은 콘솔 로그 경고만 지원합니다.**  
> Slack 알림, 이메일 등은 로그 파일을 외부 도구(Fluentd, Filebeat 등)로 수집하여 처리하세요.

### `alert.heap.percent`

| 항목 | 값 |
|------|----|
| 기본값 | `85` |
| 단위 | % |

Heap 사용률이 이 값을 초과하면 경고를 출력합니다.

```properties
# 90% 초과 시 경고
alert.heap.percent=90
```

---

### `alert.cpu.percent`

| 항목 | 값 |
|------|----|
| 기본값 | `80` |
| 단위 | % |

프로세스 CPU 사용률 임계치입니다.

```properties
alert.cpu.percent=75
```

---

### `alert.error.rate.percent`

| 항목 | 값 |
|------|----|
| 기본값 | `10` |
| 단위 | % |

트랜잭션 스팬 오류율(오류 스팬 / 전체 스팬 × 100) 임계치입니다.

```properties
# 5% 초과 시 경고
alert.error.rate.percent=5
```

---

## 5. JVM 실행 옵션

`monitor.properties`와 별도로 `java` 명령 실행 시 JVM 옵션을 지정합니다.

### 권장 운영 옵션

```bash
java \
  -Xms256m \
  -Xmx512m \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=./logs/heapdump.hprof \
  -Xlog:gc*:file=./logs/gc.log:time:filecount=3,filesize=10m \
  -Djava.util.logging.config.file=./bin/logging.properties \
  -jar java-monitor-1.12.2.jar
```

### 옵션 설명

| 옵션 | 설명 |
|------|------|
| `-Xms256m` | 초기 힙 크기 |
| `-Xmx512m` | 최대 힙 크기 |
| `-XX:+UseG1GC` | G1 GC 사용 (Java 9+ 기본값) |
| `-XX:MaxGCPauseMillis=200` | GC 최대 일시 정지 목표 시간 |
| `-XX:+HeapDumpOnOutOfMemoryError` | OOM 발생 시 힙 덤프 저장 |
| `-XX:HeapDumpPath=./logs/` | 힙 덤프 저장 경로 |
| `-Xlog:gc*:file=./logs/gc.log` | GC 로그 파일 저장 |
| `-Djava.util.logging.config.file=` | JUL 로그 설정 파일 경로 |

### `bin\start.bat` (Windows 더블클릭 실행)

`bin\start.bat`을 사용하면 위 옵션이 자동 적용됩니다.

```
java-monitor-1.12.2.jar 더블클릭 ❌  →  bin\start.bat 더블클릭 ✅
```

`start.bat`은 실행 3초 후 브라우저에서 대시보드를 자동으로 엽니다.  
백그라운드 실행은 `bin\startup.bat` / `bin\startup.sh` 를 사용하세요.

---

## 6. 로그 설정

로그 설정은 `bin/logging.properties` 파일로 제어합니다.

### 기본 구성

```properties
# 핸들러: 콘솔 + 파일
handlers=java.util.logging.ConsoleHandler, java.util.logging.FileHandler

# 전체 기본 레벨
.level=INFO

# 콘솔: INFO 이상 출력
java.util.logging.ConsoleHandler.level=INFO

# 파일: FINE 이상 출력 (스팬 상세 포함) — JUL 레벨: FINE/FINER/FINEST/CONFIG/INFO/WARNING/SEVERE
java.util.logging.FileHandler.level=FINE
java.util.logging.FileHandler.pattern=logs/monitor%u.log
java.util.logging.FileHandler.limit=10485760    # 10MB
java.util.logging.FileHandler.count=3           # 최대 3개 로테이션
java.util.logging.FileHandler.append=true

# 모니터 패키지: FINE 레벨 활성화 (SLF4J/Log4j의 DEBUG에 해당)
com.monosun.monitor.level=FINE
```

### 로그 레벨 변경

```properties
# 운영 환경: 콘솔은 WARNING만, 파일은 INFO만
java.util.logging.ConsoleHandler.level=WARNING
java.util.logging.FileHandler.level=INFO

# 개발 환경: 모든 레벨 출력
.level=FINE
```

### 로그 파일 위치

실행 디렉터리 기준 `logs/` 폴더에 저장됩니다.

```
logs/
├── monitor0.log    ← 현재 로그
├── monitor1.log    ← 이전 로그
└── monitor2.log    ← 그 이전 로그
```

### 로그 설정 파일 경로 지정

```bash
# 기본 (bin/logging.properties — bin/*.bat|*.sh 스크립트가 자동 참조)
java -Djava.util.logging.config.file=bin/logging.properties -jar ...

# 커스텀 경로
java -Djava.util.logging.config.file=/etc/myapp/logging.properties -jar ...
```

---

## 7. 시스템 속성으로 덮어쓰기 (`-D`)

`monitor.properties`의 모든 항목은 `-D` 시스템 속성으로 덮어쓸 수 있습니다.  
CI/CD 파이프라인이나 쿠버네티스 환경에서 설정 파일 없이 환경 변수처럼 사용할 수 있습니다.

```bash
# 포트 변경
java -Dserver.http.port=18080 -jar java-monitor-1.12.2.jar

# Agent 연결 활성화
java \
  -Dagent.enabled=true \
  -Dagent.host=prod-app-01 \
  -Dagent.port=7979 \
  -jar java-monitor-1.12.2.jar

# 콘솔 출력 비활성화 + 포트 변경
java \
  -Dserver.http.port=9091 \
  -Dserver.print.interval.sec=0 \
  -jar java-monitor-1.12.2.jar
```

---

## 8. 환경별 설정 예시

### 개발 환경 (`dev.properties`)

```properties
server.http.port=9090
server.print.interval.sec=5       # 자주 출력해서 빠르게 확인
server.span.buffer.size=200

agent.enabled=true
agent.host=localhost
agent.port=7979

alert.heap.percent=95             # 개발 중엔 느슨하게
alert.error.rate.percent=50
```

### 스테이징 환경 (`staging.properties`)

```properties
server.http.port=9090
server.print.interval.sec=30
server.span.buffer.size=1000

agent.enabled=true
agent.host=staging-app-01
agent.port=7979
agent.poll.interval.sec=5

alert.heap.percent=85
alert.cpu.percent=80
alert.error.rate.percent=10
```

### 운영 환경 (`prod.properties`)

```properties
server.http.port=9090
server.print.interval.sec=60      # 로그 최소화
server.span.buffer.size=5000      # 더 많은 스팬 보관

agent.enabled=true
agent.host=prod-app-01
agent.port=7979
agent.poll.interval.sec=5

alert.heap.percent=80             # 운영은 더 엄격하게
alert.cpu.percent=70
alert.error.rate.percent=5
```

실행:
```bash
java -Xms512m -Xmx2g -XX:+UseG1GC \
     -jar java-monitor-1.12.2.jar /etc/monitor/prod.properties
```

---

## 9. 트러블슈팅

### 포트 충돌: `Address already in use`

9090 포트가 이미 사용 중입니다.

```bash
# 어떤 프로세스가 사용 중인지 확인
# Linux / macOS
lsof -i :9090

# Windows
netstat -ano | findstr ":9090"
```

`monitor.properties`에서 포트를 변경하거나 `-Dserver.http.port=9091`로 덮어씁니다.

---

### 대시보드가 열리지 않음

브라우저에서 `http://localhost:9090/health` 접속 후 응답을 확인합니다.

```json
{"status":"UP","uptime_ms":12345}
```

`UP` 이면 서버는 정상입니다. 방화벽이나 프록시 설정을 확인하세요.

---

### 콘솔에 출력이 없음

`server.print.interval.sec=0` 이거나 로그 레벨이 `WARNING` 이상으로 설정된 경우입니다.

```properties
# monitor.properties
server.print.interval.sec=15

# logging.properties
java.util.logging.ConsoleHandler.level=INFO
com.monosun.monitor.level=INFO
```

---

### JVM 메트릭에서 CPU가 `-1%`로 표시됨

`com.sun.management.OperatingSystemMXBean`을 지원하지 않는 JVM(일부 OpenJ9, GraalVM 등)에서 발생합니다. HotSpot JVM(Oracle JDK, Eclipse Temurin 등)을 사용하면 정상 표시됩니다.

---

## 전체 설정 항목 요약

```properties
# ── HTTP 서버 ──────────────────────────────────────────────────
server.http.port=9090
server.print.interval.sec=15
server.span.buffer.size=1000

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
