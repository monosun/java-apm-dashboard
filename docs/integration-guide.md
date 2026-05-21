# 모니터링 대상 연동 가이드

이 문서는 **Java APM Dashboard v1.4.0**이 외부 JVM(Tomcat, Spring Boot 등)을 JMX 또는 Agent HTTP 방식으로 모니터링하는 방법을 설명합니다.

---

## 목차
1. [공통 원리 — JMX Remote](#1-공통-원리--jmx-remote)
2. [Apache Tomcat](#2-apache-tomcat)
3. [Spring Boot (Embedded Tomcat)](#3-spring-boot-embedded-tomcat)
4. [Spring Boot (JAR, 독립 실행)](#4-spring-boot-jar-독립-실행)
5. [일반 Java 프로세스](#5-일반-java-프로세스)
6. [monitor.properties 설정](#6-monitorproperties-설정)
7. [Agent Library 부착 (JMX 없이 HTTP 모니터링)](#7-agent-library-부착-jmx-없이-http-모니터링)
8. [DB 커넥션 풀 MBean 활성화](#8-db-커넥션-풀-mbean-활성화)
9. [보안 설정 (운영 환경)](#9-보안-설정-운영-환경)
10. [문제 해결](#10-문제-해결)

---

## 1. 공통 원리 — JMX Remote

JMX Remote를 활성화하려면 대상 JVM에 아래 시스템 속성을 추가합니다.

**최소 설정 (인증 없음, 개발용)**
```
-Dcom.sun.management.jmxremote
-Dcom.sun.management.jmxremote.port=9999
-Dcom.sun.management.jmxremote.authenticate=false
-Dcom.sun.management.jmxremote.ssl=false
-Dcom.sun.management.jmxremote.local.only=false
-Djava.rmi.server.hostname=<대상_서버_IP>
```

> `java.rmi.server.hostname`은 원격 접속 시 반드시 실제 IP 또는 접근 가능한 hostname으로 설정해야 합니다.

---

## 2. Apache Tomcat

### 방법 A: setenv.sh / setenv.bat

**Linux / macOS** (`$CATALINA_HOME/bin/setenv.sh`)
```bash
export CATALINA_OPTS="$CATALINA_OPTS \
  -Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.port=9999 \
  -Dcom.sun.management.jmxremote.rmi.port=9999 \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false \
  -Dcom.sun.management.jmxremote.local.only=false \
  -Djava.rmi.server.hostname=192.168.1.100"
```

**Windows** (`%CATALINA_HOME%\bin\setenv.bat`)

`setenv.bat`은 Tomcat에 기본으로 포함되지 않습니다.  
`%CATALINA_HOME%\bin\` 디렉터리에 **직접 새 파일을 만들어야** 합니다.  
`catalina.bat`이 시작 시 이 파일을 자동으로 불러옵니다.

아래 내용으로 `%CATALINA_HOME%\bin\setenv.bat` 파일을 새로 만듭니다:
```bat
@echo off
set CATALINA_OPTS=%CATALINA_OPTS% ^
  -Dcom.sun.management.jmxremote ^
  -Dcom.sun.management.jmxremote.port=9999 ^
  -Dcom.sun.management.jmxremote.rmi.port=9999 ^
  -Dcom.sun.management.jmxremote.authenticate=false ^
  -Dcom.sun.management.jmxremote.ssl=false ^
  -Dcom.sun.management.jmxremote.local.only=false ^
  -Djava.rmi.server.hostname=192.168.1.100
```

> **Windows 팁**: `rmi.port`를 `port`와 동일하게 고정하면 방화벽에서 포트 1개만 열면 됩니다.

### 방법 B: catalina.bat 직접 수정 (Windows)

`%CATALINA_HOME%\bin\catalina.bat` 상단 `rem ----- Execute ...` 주석 바로 위에 추가:
```bat
set JAVA_OPTS=%JAVA_OPTS% -Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=9999 -Dcom.sun.management.jmxremote.rmi.port=9999 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false -Djava.rmi.server.hostname=192.168.1.100
```

### 방법 C: Windows 서비스로 설치된 Tomcat (tomcatXw.exe)

Tomcat을 Windows 서비스로 설치한 경우 `setenv.bat`은 적용되지 않습니다.  
대신 **Tomcat 서비스 모니터**(`tomcat10w.exe` 또는 `tomcat9w.exe`)로 JVM 옵션을 설정합니다.

1. 관리자 권한으로 `%CATALINA_HOME%\bin\tomcat10w.exe` 실행  
   (파일명은 설치된 Tomcat 버전에 따라 `tomcat9w.exe`, `tomcat10w.exe` 등으로 다릅니다)
2. **Java** 탭 → **Java Options** 입력란 맨 아래에 한 줄씩 추가:
   ```
   -Dcom.sun.management.jmxremote
   -Dcom.sun.management.jmxremote.port=9999
   -Dcom.sun.management.jmxremote.rmi.port=9999
   -Dcom.sun.management.jmxremote.authenticate=false
   -Dcom.sun.management.jmxremote.ssl=false
   -Dcom.sun.management.jmxremote.local.only=false
   -Djava.rmi.server.hostname=192.168.1.100
   ```
3. **확인** → 서비스 재시작

또는 명령줄로 직접 등록할 수도 있습니다 (관리자 PowerShell):
```powershell
& "$env:CATALINA_HOME\bin\tomcat10.exe" //US//Tomcat10 `
  --JvmOptions="-Dcom.sun.management.jmxremote;-Dcom.sun.management.jmxremote.port=9999;-Dcom.sun.management.jmxremote.rmi.port=9999;-Dcom.sun.management.jmxremote.authenticate=false;-Dcom.sun.management.jmxremote.ssl=false;-Djava.rmi.server.hostname=192.168.1.100"
```

> `//US//Tomcat10`의 서비스 이름은 `services.msc`에서 실제 서비스 이름을 확인하세요.

### Windows 방화벽 포트 열기

JMX 포트(9999)가 방화벽에서 차단되면 원격 연결이 불가능합니다.  
관리자 권한 PowerShell 또는 cmd에서 실행합니다:

```powershell
# 인바운드 규칙 추가 (PowerShell)
New-NetFirewallRule -DisplayName "Tomcat JMX 9999" `
  -Direction Inbound -Protocol TCP -LocalPort 9999 -Action Allow

# 또는 netsh (cmd)
netsh advfirewall firewall add rule name="Tomcat JMX 9999" ^
  protocol=TCP dir=in localport=9999 action=allow
```

### Tomcat 재시작 후 연결 확인

```powershell
# Windows — 포트가 열렸는지 확인
Test-NetConnection -ComputerName 192.168.1.100 -Port 9999

# Windows — 로컬에서 수신 중인지 확인
netstat -ano | findstr ":9999"

# JConsole 연결 테스트 (JDK 설치된 경우)
jconsole service:jmx:rmi:///jndi/rmi://192.168.1.100:9999/jmxrmi
```

---

## 3. Spring Boot (Embedded Tomcat)

### application.properties / application.yml

Spring Boot Actuator + JMX 설정:

**application.properties**
```properties
# Actuator 엔드포인트 활성화 (선택)
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.show-details=always

# JMX 활성화 (Spring Boot 기본 비활성화된 경우)
spring.jmx.enabled=true
```

**JVM 시작 인수로 JMX Remote 추가:**
```bash
java -jar myapp.jar \
  -Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.port=9999 \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false \
  -Djava.rmi.server.hostname=192.168.1.100
```

### Maven / Gradle 실행 시

**Maven Spring Boot Plugin**
```bash
mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=9999 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false"
```

**Gradle Spring Boot Plugin**
```groovy
// build.gradle
bootRun {
    jvmArgs = [
        '-Dcom.sun.management.jmxremote',
        '-Dcom.sun.management.jmxremote.port=9999',
        '-Dcom.sun.management.jmxremote.authenticate=false',
        '-Dcom.sun.management.jmxremote.ssl=false',
        '-Djava.rmi.server.hostname=192.168.1.100'
    ]
}
```

---

## 4. Spring Boot (JAR, 독립 실행)

```bash
java \
  -Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.port=9999 \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false \
  -Dcom.sun.management.jmxremote.local.only=false \
  -Djava.rmi.server.hostname=<서버_IP> \
  -jar myapp.jar
```

### systemd 서비스 파일 예시

```ini
# /etc/systemd/system/myapp.service
[Unit]
Description=My Spring Boot Application
After=network.target

[Service]
Type=simple
User=appuser
WorkingDirectory=/opt/myapp
ExecStart=/usr/bin/java \
  -Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.port=9999 \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false \
  -Djava.rmi.server.hostname=192.168.1.100 \
  -jar myapp.jar
Restart=always

[Install]
WantedBy=multi-user.target
```

---

## 5. 일반 Java 프로세스

```bash
java -cp myapp.jar \
  -Dcom.sun.management.jmxremote.port=9999 \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false \
  com.example.MainClass
```

---

## 6. monitor.properties 설정

APM 에이전트의 `monitor.properties`에서 원격 대상을 지정합니다:

```properties
# ─── 원격 JVM 연결 설정 ───────────────────────────────────────
remote.jmx.enabled=true
remote.jmx.host=192.168.1.100
remote.jmx.port=9999

# 인증 (jmxremote.authenticate=true 인 경우)
remote.jmx.user=
remote.jmx.password=

# 폴링 주기 (초)
remote.jmx.poll.interval.sec=5
remote.jmx.reconnect.interval.sec=30

# ─── APM 에이전트 HTTP 서버 ────────────────────────────────────
server.http.port=9090
server.print.interval.sec=15

# ─── 경고 임계치 ──────────────────────────────────────────────
alert.heap.percent=85
alert.cpu.percent=80
alert.error.rate.percent=10
```

---

## 7. Agent Library 부착 (JMX 없이 HTTP 모니터링)

JMX 포트를 열 수 없는 환경에서 순수 HTTP 방식으로 스레드·JVM 정보를 수집합니다.

### 정적 부착 (`-javaagent:`)

JVM 시작 시 `-javaagent:` 옵션으로 부착합니다.

```bash
java -javaagent:java-monitor-1.4.0-agent.jar=port=7979 -jar myapp.jar
```

기본 포트(7979)를 사용하면 `=port=7979` 인수를 생략할 수 있습니다.

```bash
java -javaagent:java-monitor-1.4.0-agent.jar -jar myapp.jar
```

**Spring Boot (systemd 서비스)**

```ini
ExecStart=/usr/bin/java \
  -javaagent:/opt/monitor/java-monitor-1.4.0-agent.jar=port=7979 \
  -jar myapp.jar
```

**Tomcat — Linux/macOS (`setenv.sh`)**

```bash
export JAVA_OPTS="$JAVA_OPTS -javaagent:/opt/monitor/java-monitor-1.5.0-agent.jar=port=7979"
```

**Tomcat — Windows (`setenv.bat`)**

`setenv.bat`은 Tomcat에 기본 포함되지 않으므로 새로 만들어야 합니다.  
`%CATALINA_HOME%\bin\setenv.bat` 파일을 아래 내용으로 생성합니다:
```bat
@echo off
set JAVA_OPTS=%JAVA_OPTS% -javaagent:C:\monitor\java-monitor-1.5.0-agent.jar=port=7979
```

**Tomcat — Windows 서비스 (`tomcatXw.exe`)**

서비스로 설치된 Tomcat은 `tomcat10w.exe` Java 탭에서 추가합니다:
```
-javaagent:C:\monitor\java-monitor-1.5.0-agent.jar=port=7979
```

Agent 포트(7979)도 방화벽에서 열어야 대시보드가 수집할 수 있습니다:
```powershell
New-NetFirewallRule -DisplayName "Java Monitor Agent 7979" `
  -Direction Inbound -Protocol TCP -LocalPort 7979 -Action Allow
```

### 동적 부착 (실행 중인 JVM에 Attach)

JVM PID를 알면 재시작 없이 부착할 수 있습니다.

```java
import com.sun.tools.attach.VirtualMachine;

VirtualMachine vm = VirtualMachine.attach("12345");  // PID
vm.loadAgent("/opt/monitor/java-monitor-1.4.0-agent.jar", "port=7979");
vm.detach();
```

또는 `jattach` CLI 도구를 사용합니다.

```bash
# PID 확인
jps -l

# 동적 부착
jattach <PID> load instrument false /opt/monitor/java-monitor-1.4.0-agent.jar=port=7979
```

### Agent 노출 엔드포인트 확인

```bash
curl http://target-host:7979/agent/health
# {"status":"UP","port":7979}

curl http://target-host:7979/agent/threads
# [...스레드 목록 JSON...]
```

### monitor.properties에서 연결

```properties
agent.enabled=true
agent.host=target-host-or-ip
agent.port=7979
agent.poll.interval.sec=5
```

대시보드 `http://localhost:9090/dashboard` 에서 **Agent 연결 대상** 섹션을 확인합니다.

### Agent 엔드포인트 목록

| URL | 설명 |
|-----|------|
| `GET /agent/health` | 헬스 체크 |
| `GET /agent/info` | JVM·에이전트 메타정보 |
| `GET /agent/jvm` | JVM 메트릭 JSON |
| `GET /agent/threads` | 스레드 목록 JSON |
| `GET /agent/thread/{id}` | 단일 스레드 전체 스택 JSON |
| `GET /agent/threaddump` | 전체 Thread Dump (text/plain) |
| `GET /agent/deadlocks` | 데드락 감지 JSON |
| `GET /agent/requests` | Tomcat HTTP 요청 현황 JSON |
| `GET /agent/dbpools` | DB 커넥션 풀 상태 JSON |

---

## 8. DB 커넥션 풀 MBean 활성화

DB 커넥션 풀 모니터링은 JMX MBean 등록이 필요합니다. JMX 연결 또는 Agent 모드 모두 대상 JVM의 로컬 MBeanServer를 통해 수집합니다.

### HikariCP (Spring Boot)

```yaml
# application.yml
spring:
  datasource:
    hikari:
      register-mbeans: true
```

또는 `application.properties`:

```properties
spring.datasource.hikari.register-mbeans=true
```

등록 후 JConsole 등에서 `com.zaxxer.hikari:type=PoolStats,*` MBean이 보이면 정상입니다.

### Tomcat JDBC Pool

```xml
<!-- context.xml 또는 DataSource 설정 -->
<Resource name="jdbc/myds"
          type="javax.sql.DataSource"
          factory="org.apache.tomcat.jdbc.pool.DataSourceFactory"
          jmxEnabled="true"
          ... />
```

### Apache Commons DBCP2

```java
// 프로그래매틱 설정
BasicDataSource ds = new BasicDataSource();
// DBCP2는 MBeanServer에 자동 등록 (별도 설정 불필요)
// ObjectName: org.apache.commons.pool2:type=GenericObjectPool,*
```

### 확인 방법

```bash
# JMX 연결 시
curl http://localhost:9090/remote/dbpools

# Agent 모드 시
curl http://localhost:9090/agent/dbpools

# 응답 예시
# [{"poolName":"HikariPool-1","activeConnections":3,"idleConnections":7,
#   "totalConnections":10,"maxPoolSize":10,"pendingThreads":0}]
```

---

## 9. 보안 설정 (운영 환경)

### 인증 활성화

**1. jmxremote.password 파일 생성**
```
# $JAVA_HOME/conf/management/jmxremote.password (또는 커스텀 경로)
monitorRole  yourPassword123
controlRole  yourPassword456
```
```bash
chmod 600 jmxremote.password
```

**2. jmxremote.access 파일 확인**
```
# $JAVA_HOME/conf/management/jmxremote.access
monitorRole   readonly
controlRole   readwrite
```

**3. JVM 시작 인수**
```bash
-Dcom.sun.management.jmxremote.authenticate=true
-Dcom.sun.management.jmxremote.password.file=/path/to/jmxremote.password
-Dcom.sun.management.jmxremote.access.file=/path/to/jmxremote.access
```

**4. monitor.properties에 인증 정보 설정**
```properties
remote.jmx.user=monitorRole
remote.jmx.password=yourPassword123
```

### SSL 활성화 (고급)
프로덕션 환경에서는 `jmxremote.ssl=true`와 키스토어 설정을 권장합니다.
상세 설정은 [Oracle JMX 문서](https://docs.oracle.com/en/java/javase/21/management/monitoring-and-management-using-jmx-technology.html)를 참고하세요.

---

## 10. 문제 해결

| 증상 | 원인 / 해결 방법 |
|------|-----------------|
| `Connection refused` | 방화벽에서 JMX 포트 차단 — 포트 9999 오픈 |
| `ConnectException: Connection timed out` | `java.rmi.server.hostname` 미설정 또는 잘못된 IP |
| `Authentication failed` | password 파일 권한 또는 인증 정보 확인 |
| 대시보드에 `연결 끊김` 표시 | 대상 JVM이 종료되었거나 GC로 응답 지연 — reconnect 자동 시도 |
| Windows에서 JMX 접속 안 됨 | 방화벽 인바운드 규칙에 9999 포트 추가 (아래 참고) |
| Windows 서비스 Tomcat에 JMX 옵션 안 먹힘 | `setenv.bat`은 서비스 모드에서 무시됨 — `tomcatXw.exe` Java 탭에서 직접 설정 |
| Windows에서 포트 수신 여부 확인 | `netstat -ano \| findstr ":9999"` 또는 `Test-NetConnection -Port 9999` |

### Windows 방화벽 문제 상세

포트가 열려 있는지 확인합니다:
```powershell
# 로컬에서 수신 중인지 확인
netstat -ano | findstr ":9999"

# 원격에서 접근 가능한지 확인
Test-NetConnection -ComputerName <대상_IP> -Port 9999

# 방화벽 규칙 목록 확인
Get-NetFirewallRule -DisplayName "*9999*" | Select-Object DisplayName, Enabled, Direction, Action
```

규칙이 없으면 추가합니다:
```powershell
# 관리자 권한 PowerShell
New-NetFirewallRule -DisplayName "Tomcat JMX 9999" `
  -Direction Inbound -Protocol TCP -LocalPort 9999 -Action Allow

# Agent 포트도 함께 열어두면 편리합니다
New-NetFirewallRule -DisplayName "Java Monitor Agent 7979" `
  -Direction Inbound -Protocol TCP -LocalPort 7979 -Action Allow
```
