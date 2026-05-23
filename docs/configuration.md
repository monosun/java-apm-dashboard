# Java APM Dashboard — 설정 매뉴얼

## 목차

1. [설정 파일 위치 및 우선순위](#1-설정-파일-위치-및-우선순위)
2. [서버 설정](#2-서버-설정-server)
3. [원격 JMX 설정](#3-원격-jmx-설정-remote)
4. [Agent HTTP 설정](#4-agent-http-설정-agent)
5. [경고 임계치 설정](#5-경고-임계치-설정-alert)
6. [JVM 실행 옵션](#6-jvm-실행-옵션)
7. [로그 설정](#7-로그-설정)
8. [시스템 속성으로 덮어쓰기](#8-시스템-속성으로-덮어쓰기--d)
9. [환경별 설정 예시](#9-환경별-설정-예시)
10. [원격 JVM 연결 단계별 설정](#10-원격-jvm-연결-단계별-설정)
11. [트러블슈팅](#11-트러블슈팅)

> **v1.6.0 신규**: 실시간 스택 트레이스 뷰에서 Tomcat RequestProcessor와 스레드명을 자동 매칭하여 처리 중인 URI·Remote IP를 배지로 표시합니다. JMX Remote 및 Agent 연결 모두 지원하며, 추가 설정은 필요하지 않습니다.

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
├── java-monitor-1.6.0.jar
├── monitor.properties        ← 이 파일이 자동으로 적용됨
├── logs/
└── start.bat / start.sh
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

## 3. 원격 JMX 설정 (`remote.*`)

원격 JVM의 메트릭을 JMX(Java Management Extensions) RMI 프로토콜로 수집합니다.

### `remote.jmx.enabled`

| 항목 | 값 |
|------|----|
| 기본값 | `false` |
| 유형 | `true` / `false` |

원격 JVM 모니터링 기능을 켜고 끕니다.

```properties
remote.jmx.enabled=true
```

`false`일 때 `/remote/jvm`, `/remote/status` 엔드포인트는 "미설정" 응답을 반환합니다.

---

### `remote.jmx.host` / `remote.jmx.port`

| 항목 | 값 |
|------|----|
| 호스트 기본값 | `localhost` |
| 포트 기본값 | `9999` |

모니터링할 대상 JVM의 주소와 JMX 포트입니다.

```properties
remote.jmx.host=10.0.0.5
remote.jmx.port=9999
```

> **포트는 대상 JVM의 `-Dcom.sun.management.jmxremote.port` 값과 일치해야 합니다.**

---

### `remote.jmx.user` / `remote.jmx.password`

| 항목 | 값 |
|------|----|
| 기본값 | (빈값, 인증 없음) |

JMX 인증이 설정된 대상 JVM에 접속할 때 사용합니다.

```properties
# 인증 없는 경우 (개발/테스트 환경)
remote.jmx.user=
remote.jmx.password=

# 인증 있는 경우 (운영 환경 권장)
remote.jmx.user=monitoruser
remote.jmx.password=SecurePassword123
```

---

### `remote.jmx.poll.interval.sec`

| 항목 | 값 |
|------|----|
| 기본값 | `5` |
| 유형 | 정수 (초) |

원격 JVM에서 메트릭을 수집하는 주기입니다. 대시보드의 원격 JVM 섹션은 5초마다 화면을 갱신하므로, 이 값을 너무 크게 설정하면 표시된 데이터가 오래될 수 있습니다.

```properties
# 3초마다 수집 (더 실시간)
remote.jmx.poll.interval.sec=3

# 10초마다 수집 (부하 감소)
remote.jmx.poll.interval.sec=10
```

---

### `remote.jmx.reconnect.interval.sec`

| 항목 | 값 |
|------|----|
| 기본값 | `30` |
| 유형 | 정수 (초) |

원격 JVM과 연결이 끊어졌을 때 재연결을 시도하는 주기입니다.

```properties
# 10초마다 재연결 시도
remote.jmx.reconnect.interval.sec=10
```

---

## 4. Agent HTTP 설정 (`agent.*`)

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

## 5. 경고 임계치 설정 (`alert.*`)

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

## 6. JVM 실행 옵션

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
  -Djava.util.logging.config.file=./scripts/logging.properties \
  -jar java-monitor-1.6.0.jar
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

### `start.bat` (Windows 더블클릭 실행)

JAR와 같은 디렉터리의 `start.bat`을 사용하면 위 옵션이 자동 적용됩니다.

```
java-monitor-1.6.0.jar 더블클릭 ❌  →  start.bat 더블클릭 ✅
```

`start.bat`은 실행 3초 후 브라우저에서 대시보드를 자동으로 엽니다.

---

## 7. 로그 설정

로그 설정은 `scripts/logging.properties` 파일로 제어합니다.

### 기본 구성

```properties
# 핸들러: 콘솔 + 파일
handlers=java.util.logging.ConsoleHandler, java.util.logging.FileHandler

# 전체 기본 레벨
.level=INFO

# 콘솔: INFO 이상 출력
java.util.logging.ConsoleHandler.level=INFO

# 파일: FINE 이상 출력 (스팬 상세 포함)
java.util.logging.FileHandler.level=FINE
java.util.logging.FileHandler.pattern=logs/monitor%u.log
java.util.logging.FileHandler.limit=10485760    # 10MB
java.util.logging.FileHandler.count=3           # 최대 3개 로테이션
java.util.logging.FileHandler.append=true

# 모니터 패키지: FINE 레벨 활성화
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
# 기본 (scripts/logging.properties)
java -Djava.util.logging.config.file=scripts/logging.properties -jar ...

# 커스텀 경로
java -Djava.util.logging.config.file=/etc/myapp/logging.properties -jar ...
```

---

## 8. 시스템 속성으로 덮어쓰기 (`-D`)

`monitor.properties`의 모든 항목은 `-D` 시스템 속성으로 덮어쓸 수 있습니다.  
CI/CD 파이프라인이나 쿠버네티스 환경에서 설정 파일 없이 환경 변수처럼 사용할 수 있습니다.

```bash
# 포트 변경
java -Dserver.http.port=18080 -jar java-monitor-1.6.0.jar

# 원격 JMX 활성화
java \
  -Dremote.jmx.enabled=true \
  -Dremote.jmx.host=prod-server-01 \
  -Dremote.jmx.port=9999 \
  -jar java-monitor-1.6.0.jar

# 콘솔 출력 비활성화 + 포트 변경
java \
  -Dserver.http.port=9091 \
  -Dserver.print.interval.sec=0 \
  -jar java-monitor-1.6.0.jar
```

---

## 9. 환경별 설정 예시

### 개발 환경 (`dev.properties`)

```properties
server.http.port=9090
server.print.interval.sec=5       # 자주 출력해서 빠르게 확인
server.span.buffer.size=200

remote.jmx.enabled=false

alert.heap.percent=95             # 개발 중엔 느슨하게
alert.error.rate.percent=50
```

### 스테이징 환경 (`staging.properties`)

```properties
server.http.port=9090
server.print.interval.sec=30
server.span.buffer.size=1000

remote.jmx.enabled=true
remote.jmx.host=staging-app-01
remote.jmx.port=9999
remote.jmx.poll.interval.sec=5
remote.jmx.reconnect.interval.sec=30

alert.heap.percent=85
alert.cpu.percent=80
alert.error.rate.percent=10
```

### 운영 환경 (`prod.properties`)

```properties
server.http.port=9090
server.print.interval.sec=60      # 로그 최소화
server.span.buffer.size=5000      # 더 많은 스팬 보관

remote.jmx.enabled=true
remote.jmx.host=prod-app-01
remote.jmx.port=9999
remote.jmx.user=monitoruser
remote.jmx.password=ProdSecurePass!
remote.jmx.poll.interval.sec=5
remote.jmx.reconnect.interval.sec=15

alert.heap.percent=80             # 운영은 더 엄격하게
alert.cpu.percent=70
alert.error.rate.percent=5
```

실행:
```bash
java -Xms512m -Xmx2g -XX:+UseG1GC \
     -jar java-monitor-1.6.0.jar /etc/monitor/prod.properties
```

---

## 10. 원격 JVM 연결 단계별 설정

### Step 1. 대상 JVM에 JMX 옵션 추가

모니터링할 애플리케이션 JVM 시작 명령에 아래 옵션을 추가합니다.

#### 인증 없음 (개발/내부망)

```bash
java \
  -Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.port=9999 \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false \
  -jar myapp.jar
```

#### 인증 있음 (운영 권장)

```bash
# 1. jmxremote.password 파일 생성 (JDK_HOME/conf/management/ 복사 후 수정)
cp $JAVA_HOME/conf/management/jmxremote.password.template jmxremote.password
echo "monitoruser ProdSecurePass!" >> jmxremote.password
chmod 600 jmxremote.password

# 2. jmxremote.access 파일 생성
echo "monitoruser readonly" > jmxremote.access
chmod 600 jmxremote.access

# 3. JVM 시작
java \
  -Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.port=9999 \
  -Dcom.sun.management.jmxremote.authenticate=true \
  -Dcom.sun.management.jmxremote.ssl=false \
  -Dcom.sun.management.jmxremote.password.file=./jmxremote.password \
  -Dcom.sun.management.jmxremote.access.file=./jmxremote.access \
  -jar myapp.jar
```

#### Docker 컨테이너 내 JVM

```bash
java \
  -Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.port=9999 \
  -Dcom.sun.management.jmxremote.rmi.port=9999 \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false \
  -Djava.rmi.server.hostname=<컨테이너_외부_IP> \
  -jar myapp.jar
```

> Docker 환경에서는 `-Djava.rmi.server.hostname`을 컨테이너가 외부에서 접근 가능한 IP로 반드시 설정해야 합니다.

#### Spring Boot `application.properties`

```properties
spring.jmx.enabled=true
```

JVM 시작 옵션은 별도 추가가 필요합니다.

### Step 2. 방화벽 / 포트 확인

JMX RMI는 지정한 포트 외에도 임의 포트를 추가로 사용할 수 있습니다.  
아래처럼 RMI 포트를 고정하면 방화벽 설정이 단순해집니다.

```bash
-Dcom.sun.management.jmxremote.port=9999
-Dcom.sun.management.jmxremote.rmi.port=9999   # 같은 포트로 고정
```

방화벽에서 TCP 9999 포트를 모니터 서버 IP에서만 허용합니다.

### Step 3. `monitor.properties` 설정

```properties
remote.jmx.enabled=true
remote.jmx.host=10.0.0.5        # 대상 JVM 서버 IP
remote.jmx.port=9999
remote.jmx.user=monitoruser     # 인증 없으면 빈값
remote.jmx.password=ProdSecurePass!
remote.jmx.poll.interval.sec=5
```

### Step 4. 연결 확인

모니터를 실행하고 아래 URL로 연결 상태를 확인합니다.

```bash
curl http://localhost:9090/remote/status
```

응답 예시 (연결 성공):
```json
{
  "connected": true,
  "target": "10.0.0.5:9999",
  "lastCollectedMs": 1240,
  "error": ""
}
```

응답 예시 (연결 실패):
```json
{
  "connected": false,
  "target": "10.0.0.5:9999",
  "lastCollectedMs": -1,
  "error": "Connection refused to host: 10.0.0.5"
}
```

---

## 11. 트러블슈팅

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

### 원격 JMX 연결 실패: `Connection refused`

**원인 1: 대상 JVM에 JMX 옵션이 없음**

```bash
# 대상 JVM 프로세스에 JMX 설정 여부 확인
ps aux | grep jmxremote
```

`jmxremote.port` 가 없으면 JVM 재시작 필요합니다.

**원인 2: 호스트/포트가 잘못됨**

```bash
# 포트 열림 여부 확인
telnet 10.0.0.5 9999
# 또는
nc -zv 10.0.0.5 9999
```

**원인 3: 방화벽 차단**

모니터 서버 → 대상 JVM 서버 TCP 9999 포트가 허용되어 있는지 확인합니다.

---

### 원격 JMX 연결 실패: Docker/컨테이너 환경

RMI는 클라이언트에게 연결할 호스트 주소를 전달하는데, 이 주소가 컨테이너 내부 IP로 설정되면 외부에서 접근이 불가능합니다.

```bash
# 반드시 외부에서 접근 가능한 IP로 설정
-Djava.rmi.server.hostname=<호스트_서버의_외부_IP>
```

---

### 원격 JMX 인증 오류: `Authentication failed`

`jmxremote.password` 파일 권한이 `600`이어야 합니다.

```bash
chmod 600 jmxremote.password
chmod 600 jmxremote.access
```

`monitor.properties`의 user/password가 파일에 등록된 값과 일치하는지 확인합니다.

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
