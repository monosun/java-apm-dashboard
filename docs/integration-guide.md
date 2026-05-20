# 모니터링 대상 연동 가이드

이 문서는 **Java APM Dashboard**가 외부 JVM(Tomcat, Spring Boot 등)을 JMX로 모니터링하는 방법을 설명합니다.

---

## 목차
1. [공통 원리 — JMX Remote](#1-공통-원리--jmx-remote)
2. [Apache Tomcat](#2-apache-tomcat)
3. [Spring Boot (Embedded Tomcat)](#3-spring-boot-embedded-tomcat)
4. [Spring Boot (JAR, 독립 실행)](#4-spring-boot-jar-독립-실행)
5. [일반 Java 프로세스](#5-일반-java-프로세스)
6. [monitor.properties 설정](#6-monitorproperties-설정)
7. [보안 설정 (운영 환경)](#7-보안-설정-운영-환경)
8. [문제 해결](#8-문제-해결)

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
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false \
  -Dcom.sun.management.jmxremote.local.only=false \
  -Djava.rmi.server.hostname=192.168.1.100"
```

**Windows** (`%CATALINA_HOME%\bin\setenv.bat`)
```bat
set CATALINA_OPTS=%CATALINA_OPTS% ^
  -Dcom.sun.management.jmxremote ^
  -Dcom.sun.management.jmxremote.port=9999 ^
  -Dcom.sun.management.jmxremote.authenticate=false ^
  -Dcom.sun.management.jmxremote.ssl=false ^
  -Dcom.sun.management.jmxremote.local.only=false ^
  -Djava.rmi.server.hostname=192.168.1.100
```

### 방법 B: catalina.sh 직접 수정

`$CATALINA_HOME/bin/catalina.sh` 상단에 추가:
```bash
JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote.port=9999 ..."
```

### Tomcat 재시작 후 연결 확인
```bash
# Linux
jconsole service:jmx:rmi:///jndi/rmi://192.168.1.100:9999/jmxrmi

# 또는 telnet으로 포트 확인
telnet 192.168.1.100 9999
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

## 7. 보안 설정 (운영 환경)

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

## 8. 문제 해결

| 증상 | 원인 / 해결 방법 |
|------|-----------------|
| `Connection refused` | 방화벽에서 JMX 포트 차단 — 포트 9999 오픈 |
| `ConnectException: Connection timed out` | `java.rmi.server.hostname` 미설정 또는 잘못된 IP |
| `Authentication failed` | password 파일 권한 또는 인증 정보 확인 |
| 대시보드에 `연결 끊김` 표시 | 대상 JVM이 종료되었거나 GC로 응답 지연 — reconnect 자동 시도 |
| Windows에서 접속 안 됨 | 방화벽 인바운드 규칙에 9999 포트 추가 |
