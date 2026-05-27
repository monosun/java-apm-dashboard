# Trace Context 설정 매뉴얼 — Java APM Dashboard v1.12.1

HTTP 요청의 **헤더·파라미터 전체**를 대시보드에서 실시간으로 조회하는 기능입니다.  
Trace ID 배지를 클릭하면 해당 요청의 캡처 정보가 모달로 표시됩니다.

---

## 목차

1. [동작 원리](#1-동작-원리)
2. [사전 요구사항](#2-사전-요구사항)
3. [JAR 파일 빌드 및 배포](#3-jar-파일-빌드-및-배포)
4. [대상 웹앱 설정 — Tomcat standalone](#4-대상-웹앱-설정--tomcat-standalone)
5. [대상 웹앱 설정 — Spring Boot](#5-대상-웹앱-설정--spring-boot)
6. [대시보드 설정](#6-대시보드-설정)
7. [대시보드에서 Trace Context 확인](#7-대시보드에서-trace-context-확인)
8. [설정 레퍼런스](#8-설정-레퍼런스)
9. [문제 해결](#9-문제-해결)

---

## 1. 동작 원리

```
대상 JVM (모니터링 대상 웹앱)
┌────────────────────────────────────────────────┐
│                                                │
│  HTTP 요청  ──▶  TraceIdFilter                 │
│                   ├─ traceId 추출 / 신규 생성    │
│                   ├─ request.setAttribute()    │
│                   ├─ JMX: registerTrace()       │
│                   └─ HTML에 window.__APM_TRACE_ID 주입
│                                                │
│               RequestContextCaptureFilter      │
│                   ├─ method, URI, queryString  │
│                   ├─ 헤더 (민감 헤더 제외)       │
│                   ├─ 파라미터                    │
│                   ├─ RequestContextStore.put()  │
│                   └─ TraceRegistry.registerContext(traceId, JSON)
│                                                │
│  -javaagent:agent.jar=port=7979               │
│  ┌────────────────────────────────┐            │
│  │ AgentHttpServer                │            │
│  │  GET /agent/trace/{traceId}    │            │
│  │    └─ JMX: getContext()  ──────┼──▶ TraceRegistry.CONTEXT_MAP
│  └────────────────────────────────┘            │
└────────────────────────────────────────────────┘
                │  HTTP (port 7979)
                ▼
대시보드 JVM (java-monitor.jar)
┌────────────────────────────────────────────────┐
│  AgentHttpClient                               │
│  MetricsHttpServer                             │
│   GET /agent/trace/{traceId}  (프록시)          │
└────────────────────────────────────────────────┘
                │  HTTP (port 9090)
                ▼
브라우저 — Trace ID 배지 클릭 → 모달로 헤더·파라미터 표시
```

**핵심 구성 요소**

| 컴포넌트 | 위치 | 역할 |
|---|---|---|
| `TraceIdFilter` | integration JAR → webapp classloader | traceId 생성·전파·HTML 주입 |
| `RequestContextCaptureFilter` | integration JAR → webapp classloader | 헤더·파라미터 캡처 → TraceRegistry 저장 |
| `TraceRegistry` (JMX) | integration JAR → webapp classloader | traceId ↔ 컨텍스트 JSON 저장소 (JMX 브리지) |
| `AgentHttpServer` | agent JAR → system classloader | `/agent/trace/{id}` 엔드포인트 |
| `MetricsHttpServer` | dashboard JAR | `/agent/trace/{id}` 프록시 |

---

## 2. 사전 요구사항

| 항목 | 요건 |
|---|---|
| Java | 11 이상 (Java 21 권장) |
| Servlet API | 4.0 (`javax.servlet.*`) — Tomcat 9.x 기준 |
| Maven | 3.6 이상 (빌드 시) |
| 대상 서버 포트 | **7979** (AgentHttpServer, 방화벽 허용 필요) |
| 대시보드 포트 | **9090** (브라우저 접근) |

> Jakarta EE 10 (Tomcat 10.x, `jakarta.servlet.*`) 환경이라면  
> `integration JAR` 내 import를 `jakarta.servlet.*` 으로 변경 후 재빌드해야 합니다.

---

## 3. JAR 파일 빌드 및 배포

### 3-1. 빌드

```bash
# 프로젝트 루트에서
cd /path/to/java-monitor
mvn clean package -q

# Windows
bin\build.bat
```

빌드 후 생성되는 파일:

```
target/
  java-monitor-{version}-agent.jar        ← 대상 JVM에 -javaagent:로 부착
  java-monitor-{version}-integration.jar  ← 웹앱 WEB-INF/lib에 배치
  java-monitor-{version}.jar              ← 대시보드 실행 JAR
bin/
  java-monitor-agent.jar        ← 버전 없는 복사본 (스크립트용)
  java-monitor-integration.jar
  java-monitor.jar
```

### 3-2. 파일 배포

```
/opt/apm/
├── java-monitor-agent.jar        ← 대상 JVM JVM 옵션에 경로 지정
├── java-monitor-integration.jar  ← 각 웹앱 WEB-INF/lib/에 복사
└── java-monitor.jar              ← 대시보드 실행
```

---

## 4. 대상 웹앱 설정 — Tomcat standalone

### 4-1. Agent JAR 부착 (JVM 옵션)

**Linux / macOS** — `$CATALINA_HOME/bin/setenv.sh`

```bash
export JAVA_OPTS="$JAVA_OPTS \
  -javaagent:/opt/apm/java-monitor-agent.jar=port=7979"
```

**Windows** — `%CATALINA_HOME%\bin\setenv.bat` (없으면 직접 생성)

```bat
set JAVA_OPTS=%JAVA_OPTS% -javaagent:C:\apm\java-monitor-agent.jar=port=7979
```

### 4-2. Integration JAR 배포

```bash
cp java-monitor-integration.jar  /opt/tomcat/webapps/ROOT/WEB-INF/lib/
# 또는 각 웹앱마다
cp java-monitor-integration.jar  /opt/tomcat/webapps/myapp/WEB-INF/lib/
```

### 4-3. 필터 등록 (`WEB-INF/web.xml`)

두 필터를 **이 순서대로** 선언합니다. `TraceIdFilter`가 먼저 실행되어야 합니다.

#### 완전한 web.xml 예시 (신규 프로젝트 또는 단독 웹앱)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<web-app xmlns="http://xmlns.jcp.org/xml/ns/javaee"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://xmlns.jcp.org/xml/ns/javaee
                             http://xmlns.jcp.org/xml/ns/javaee/web-app_4_0.xsd"
         version="4.0">

  <display-name>My Application</display-name>

  <!-- ══════════════════════════════════════════════════════════════
       APM — Java Monitor Trace Context
       java-monitor-integration.jar 을 WEB-INF/lib/ 에 배치한 후 활성화
       ══════════════════════════════════════════════════════════════ -->

  <!-- ① Trace ID 생성 · 전파 · HTML 주입 (window.__APM_TRACE_ID) -->
  <filter>
    <filter-name>traceIdFilter</filter-name>
    <filter-class>com.monosun.monitor.trace.TraceIdFilter</filter-class>
    <!-- 비동기 서블릿(startAsync) 사용 시 반드시 true -->
    <async-supported>true</async-supported>
    <init-param>
      <!-- true: HTML 응답 </head> 직전에 <meta>+<script> 자동 주입 -->
      <!-- false: HTML 주입 없이 Trace ID 전파만 수행               -->
      <param-name>injectHtml</param-name>
      <param-value>true</param-value>
    </init-param>
  </filter>
  <filter-mapping>
    <filter-name>traceIdFilter</filter-name>
    <url-pattern>/*</url-pattern>
  </filter-mapping>

  <!-- ② 요청 헤더·파라미터 캡처 — traceIdFilter 다음에 선언 -->
  <filter>
    <filter-name>requestContextCaptureFilter</filter-name>
    <filter-class>com.monosun.monitor.trace.RequestContextCaptureFilter</filter-class>
    <async-supported>true</async-supported>
    <init-param>
      <!-- 캡처에서 제외할 헤더 (쉼표 구분, 소문자)           -->
      <!-- 기본값: cookie,authorization                       -->
      <!-- 운영 환경에서는 민감 헤더를 추가로 제외할 것        -->
      <param-name>skipHeaders</param-name>
      <param-value>cookie,authorization,proxy-authorization</param-value>
    </init-param>
  </filter>
  <filter-mapping>
    <filter-name>requestContextCaptureFilter</filter-name>
    <url-pattern>/*</url-pattern>
  </filter-mapping>

  <!-- ══════════════════════════════════════════════════════════════
       애플리케이션 서블릿 설정
       ══════════════════════════════════════════════════════════════ -->

  <servlet>
    <servlet-name>dispatcher</servlet-name>
    <servlet-class>org.springframework.web.servlet.DispatcherServlet</servlet-class>
    <init-param>
      <param-name>contextConfigLocation</param-name>
      <param-value>/WEB-INF/spring/dispatcher-servlet.xml</param-value>
    </init-param>
    <load-on-startup>1</load-on-startup>
    <async-supported>true</async-supported>
  </servlet>
  <servlet-mapping>
    <servlet-name>dispatcher</servlet-name>
    <url-pattern>/</url-pattern>
  </servlet-mapping>

  <!-- 세션 타임아웃 (선택) -->
  <session-config>
    <session-timeout>30</session-timeout>
  </session-config>

</web-app>
```

#### 기존 web.xml에 추가하는 경우 (삽입 위치)

이미 `web.xml`이 있는 프로젝트라면 아래 두 블록을 **기존 `<filter>` 선언보다 앞**에 추가합니다.  
`<filter-mapping>` 선언 순서가 필터 실행 순서를 결정하므로,  
APM 필터 두 개의 `<filter-mapping>`이 다른 필터보다 앞에 위치해야 합니다.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<web-app ...>

  <!-- ▼▼▼ 여기에 삽입 — 기존 filter/filter-mapping보다 앞 ▼▼▼ -->

  <filter>
    <filter-name>traceIdFilter</filter-name>
    <filter-class>com.monosun.monitor.trace.TraceIdFilter</filter-class>
    <async-supported>true</async-supported>
    <init-param>
      <param-name>injectHtml</param-name>
      <param-value>true</param-value>
    </init-param>
  </filter>
  <filter-mapping>
    <filter-name>traceIdFilter</filter-name>
    <url-pattern>/*</url-pattern>
  </filter-mapping>

  <filter>
    <filter-name>requestContextCaptureFilter</filter-name>
    <filter-class>com.monosun.monitor.trace.RequestContextCaptureFilter</filter-class>
    <async-supported>true</async-supported>
    <init-param>
      <param-name>skipHeaders</param-name>
      <param-value>cookie,authorization,proxy-authorization</param-value>
    </init-param>
  </filter>
  <filter-mapping>
    <filter-name>requestContextCaptureFilter</filter-name>
    <url-pattern>/*</url-pattern>
  </filter-mapping>

  <!-- ▲▲▲ 여기까지 삽입 ▲▲▲ -->

  <!-- 기존 필터·서블릿 설정 계속 -->
  ...

</web-app>
```

> **`<async-supported>true</async-supported>` 필수 여부**  
> Spring MVC의 `DeferredResult`, `CompletableFuture`, WebFlux 등 비동기 처리를 사용한다면  
> 반드시 `true`로 설정해야 합니다. 없으면 `IllegalStateException: startAsync is not supported` 오류가 발생합니다.  
> REST API 전용 서버(HTML 주입 불필요)라면 `injectHtml`을 `false`로 설정해도 됩니다.

### 4-4. Tomcat 재시작

```bash
$CATALINA_HOME/bin/shutdown.sh && $CATALINA_HOME/bin/startup.sh
```

Agent 서버 기동 확인:

```bash
curl http://localhost:7979/agent/health
# {"status":"UP","port":7979}
```

---

## 5. 대상 웹앱 설정 — Spring Boot

### 5-1. JVM 옵션

```bash
java -javaagent:/opt/apm/java-monitor-agent.jar=port=7979 \
     -jar myapp.jar
```

또는 `application.properties`:

```properties
spring.jvm.args=-javaagent:/opt/apm/java-monitor-agent.jar=port=7979
```

IntelliJ IDEA의 경우 실행 구성(Run Configuration) → VM options에 추가합니다.

### 5-2. Integration JAR 의존성 추가

`pom.xml` (로컬 파일 참조):

```xml
<dependency>
  <groupId>com.monosun</groupId>
  <artifactId>java-monitor-integration</artifactId>
  <version>1.12.1</version>
  <scope>system</scope>
  <systemPath>${project.basedir}/lib/java-monitor-integration.jar</systemPath>
</dependency>
```

또는 빌드 산출물을 로컬 Maven 리포지토리에 설치:

```bash
mvn install:install-file \
  -Dfile=java-monitor-integration.jar \
  -DgroupId=com.monosun \
  -DartifactId=java-monitor-integration \
  -Dversion=1.12.1 \
  -Dpackaging=jar
```

### 5-3. 필터 빈 등록 (`@Configuration`)

```java
import com.monosun.monitor.trace.TraceIdFilter;
import com.monosun.monitor.trace.RequestContextCaptureFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class ApmConfig {

    /** ① Trace ID 생성·전파 — 가장 먼저 실행 */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new TraceIdFilter());
        reg.addUrlPatterns("/*");
        reg.addInitParameter("injectHtml", "true");
        reg.setAsyncSupported(true);
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    /** ② 헤더·파라미터 캡처 — TraceIdFilter 다음에 실행 */
    @Bean
    public FilterRegistrationBean<RequestContextCaptureFilter> requestContextFilter() {
        FilterRegistrationBean<RequestContextCaptureFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new RequestContextCaptureFilter());
        reg.addUrlPatterns("/*");
        // 제외할 헤더 (민감 정보 보호). 기본값: cookie,authorization
        reg.addInitParameter("skipHeaders", "cookie,authorization,proxy-authorization");
        reg.setAsyncSupported(true);
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return reg;
    }
}
```

### 5-4. 애플리케이션 기동 확인

```bash
# Agent HTTP 서버 동작 확인
curl http://localhost:7979/agent/health
# {"status":"UP","port":7979}

# 요청 컨텍스트 캡처 확인 (요청 후)
curl "http://localhost:8080/api/hello?name=world" -H "X-Custom-Header: test"
# 이후 대시보드에서 해당 Trace ID 배지 클릭
```

---

## 6. 대시보드 설정

### 6-1. `bin/monitor.properties`

```properties
# 대시보드 HTTP 포트
server.http.port=9090

# Agent 연결 설정 — 대상 JVM과 일치시킬 것
agent.enabled=true
agent.host=localhost          # 대상 서버 IP 또는 hostname
agent.port=7979               # -javaagent에서 지정한 port와 동일
agent.poll.interval.sec=5
```

> `agent.host`는 대시보드 서버에서 대상 JVM 서버로 접근 가능한 주소여야 합니다.  
> 서로 다른 서버라면 방화벽에서 7979 포트를 허용하세요.

### 6-2. 대시보드 기동

**Windows**

```bat
bin\startup.bat
```

**Linux / macOS**

```bash
bin/startup.sh
```

**직접 실행**

```bash
java -Djava.util.logging.config.file=bin/logging.properties \
     -jar bin/java-monitor.jar \
     bin/monitor.properties
```

### 6-3. 기동 확인

```bash
curl http://localhost:9090/health
# {"status":"UP","uptime_ms":...}

curl http://localhost:9090/agent/status
# {"connected":true,"target":"localhost:7979",...}
```

브라우저에서 `http://localhost:9090/dashboard` 접속 후  
헤더의 연결 상태 pill이 **녹색**으로 표시되면 정상입니다.

---

## 7. 대시보드에서 Trace Context 확인

### 7-1. HTTP 요청 테이블에서 확인

1. 대시보드 접속 → **[2] Agent 연결 대상** 섹션
2. **활성 HTTP 요청 (Agent RequestProcessor)** 테이블의 **Trace ID** 열
3. 초록 배지 클릭 → **Trace Context 모달** 팝업

```
┌─────────────────────────────────────────────────────────────────┐
│ Trace Context — a3f1b2c4d5e6f7a8                                │
├─────────────────────────────────────────────────────────────────┤
│ 요청 정보                                                         │
│   GET  /api/orders?status=active                                │
│   Remote IP: 192.168.1.42    캡처 시각: 14:32:07                 │
├─────────────────────────────────────────────────────────────────┤
│ HTTP 헤더 (6)                                                     │
│   host            │ api.example.com                             │
│   accept          │ application/json                            │
│   x-trace-id      │ a3f1b2c4d5e6f7a8b9c0d1e2f3a4b5c6          │
│   x-user-id       │ user-42                                     │
│   content-type    │ application/json                            │
│   accept-language │ ko-KR,ko;q=0.9                              │
├─────────────────────────────────────────────────────────────────┤
│ Request 파라미터 (2)                                              │
│   status  │ active                                              │
│   page    │ 1                                                   │
└─────────────────────────────────────────────────────────────────┘
```

### 7-2. 요청 상세 모달에서 확인

HTTP 요청 테이블 행 전체를 클릭하면 **HTTP 요청 상세** 모달이 열립니다.  
모달 상단의 **초록 Trace ID 배지**를 클릭하면 동일한 Trace Context 모달로 이동합니다.

### 7-3. `window.__APM_TRACE_ID` 활용

`TraceIdFilter`가 HTML 응답의 `</head>` 직전에 다음을 주입합니다:

```html
<meta name="trace-id" content="a3f1b2c4d5e6f7a8...">
<script>window.__APM_TRACE_ID='a3f1b2c4d5e6f7a8...';</script>
```

브라우저 콘솔 또는 프론트엔드 코드에서 이 값을 이용해 대시보드 API를 직접 호출할 수 있습니다:

```javascript
// 브라우저 콘솔에서 현재 페이지의 Trace Context 조회
const traceId = window.__APM_TRACE_ID
             || document.querySelector('meta[name="trace-id"]')?.content;

const ctx = await fetch(`http://dashboard-host:9090/agent/trace/${traceId}`)
  .then(r => r.json());

console.log('요청 헤더:', ctx.headers);
console.log('파라미터:', ctx.params);
```

---

## 8. 설정 레퍼런스

### `RequestContextCaptureFilter` init-param

| 파라미터 | 기본값 | 설명 |
|---|---|---|
| `skipHeaders` | `cookie,authorization` | 캡처에서 제외할 헤더 (쉼표 구분, 소문자). 민감 정보 보호용 |

기본 제외 헤더: `cookie`, `authorization`  
운영 환경에서 추가 제외를 권장하는 헤더: `proxy-authorization`, `x-api-key`, `x-auth-token`

### Trace Context JSON 구조

`/agent/trace/{traceId}` 응답 예시:

```json
{
  "traceId": "a3f1b2c4d5e6f7a8b9c0d1e2f3a4b5c6",
  "method": "POST",
  "uri": "/api/orders",
  "queryString": "dryRun=true",
  "remoteAddr": "192.168.1.42",
  "headers": {
    "host": "api.example.com",
    "accept": "application/json",
    "content-type": "application/json; charset=UTF-8",
    "x-user-id": "user-42",
    "x-request-id": "req-abc123"
  },
  "params": {
    "dryRun": ["true"]
  },
  "capturedAt": 1716600727000
}
```

> `cookie`, `authorization` 헤더는 `skipHeaders` 기본값에 의해 제외됩니다.  
> 오류 시 `{"error": "trace context not found"}` 또는 `{"error": "..."}` 반환.

### `monitor.properties` Agent 관련 설정

```properties
agent.enabled=true                # false이면 Agent 연결 비활성화
agent.host=localhost              # 대상 JVM 호스트
agent.port=7979                   # 대상 JVM Agent 포트 (-javaagent=port=?와 동일)
agent.poll.interval.sec=5         # 데이터 수집 주기 (초)
```

---

## 9. 문제 해결

### Trace ID 배지가 표시되지 않는다

| 확인 사항 | 해결 |
|---|---|
| Agent 연결 상태 (`/agent/status`) | `agent.enabled=true`, `agent.host`/`agent.port` 확인 |
| Tomcat RequestProcessor 노출 여부 | Tomcat 9.x 기본 제공, Jetty는 미지원 |
| `TraceIdFilter` 미등록 | `web.xml` 또는 `@Bean` 등록 확인 |

### 배지를 클릭해도 "trace context not found" 오류

| 원인 | 설명 |
|---|---|
| `RequestContextCaptureFilter` 미등록 | 필터가 없으면 컨텍스트가 저장되지 않음 |
| 요청이 이미 완료됨 | 컨텍스트는 요청 처리 중에만 유지됨. 완료 후 자동 삭제 |
| `TraceRegistry` MBean 미등록 | Agent JAR 없이 Integration JAR만 배포한 경우 |

> Trace Context는 **요청이 처리 중인 시간 동안만** 조회 가능합니다.  
> 응답이 완료되면 컨텍스트는 자동으로 제거됩니다.  
> 장시간 처리 요청(배치, 스트리밍)에서 조회 효과가 가장 큽니다.

### Agent HTTP 서버가 응답하지 않는다

```bash
# 포트 사용 확인
netstat -an | grep 7979       # Linux
netstat -ano | findstr 7979   # Windows

# Agent 로그 확인
# JVM 기동 로그에서 "[ThreadMonitorAgent] 시작 — port:7979" 확인
```

`-javaagent:` 옵션이 적용되지 않은 경우 Agent 서버가 기동되지 않습니다.  
JVM 프로세스 실행 명령에 옵션이 포함되었는지 확인합니다:

```bash
ps aux | grep java | grep javaagent   # Linux
wmic process where "name='java.exe'" get commandline   # Windows
```

### TraceIdFilter와 RequestContextCaptureFilter 순서 오류

`RequestContextCaptureFilter`는 `request.getAttribute(TraceIdFilter.REQUEST_ATTR)`로  
traceId를 읽습니다. `TraceIdFilter`가 먼저 실행되지 않으면 traceId가 `null`이 되어  
컨텍스트 캡처가 건너뜁니다.

`web.xml`에서 `<filter-mapping>` 선언 순서가 실행 순서를 결정합니다.  
Spring Boot에서는 `setOrder()` 값이 낮을수록 먼저 실행됩니다.

```
TraceIdFilter    → order = Ordered.HIGHEST_PRECEDENCE      (숫자가 가장 작음)
RequestContext.. → order = Ordered.HIGHEST_PRECEDENCE + 1
```

### 헤더가 캡처되지 않는다 (특정 헤더 누락)

`skipHeaders` 설정에 해당 헤더가 포함되어 있는지 확인합니다.  
소문자로 비교하므로 `Authorization` → `authorization` 으로 지정합니다.

```xml
<!-- 모든 헤더 캡처 (보안 주의 — 운영 환경에서는 민감 헤더 제외 권장) -->
<init-param>
  <param-name>skipHeaders</param-name>
  <param-value></param-value>
</init-param>
```

---

## 관련 문서

- [Trace ID 연동 가이드](trace-id-guide.md) — TraceIdFilter 상세 설정
- [TraceId로 Request 파라미터·헤더 조회하기](trace-id-request-context.md) — 서버 코드에서 컨텍스트 조회
- [아키텍처 개요](architecture.md) — 전체 시스템 구조
- [설정 레퍼런스](configuration.md) — monitor.properties 전체 옵션
