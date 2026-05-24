# Trace ID 연동 가이드 — Java APM Dashboard v1.10.0

Java APM Dashboard는 **TraceIdFilter** + **TraceRegistry** 조합으로  
웹 애플리케이션의 트레이스 ID를 대시보드에서 실시간으로 확인할 수 있습니다.

---

## 동작 원리

```
빌드 산출물
   ├─ java-monitor-{version}-agent.jar       ← -javaagent: 로 부착 (system classloader)
   │     TraceRegistry (JMX DynamicMBean)
   │     AgentHttpServer, AgentThreadCollector, ...
   │
   └─ java-monitor-{version}-integration.jar ← WEB-INF/lib 에 배치 (webapp classloader)
         TraceIdFilter, HtmlInjectionWrapper, TraceRegistry

                    ┌──────────────────────┐
HTTP 요청 ──────▶   │ TraceIdFilter         │  (webapp classloader)
                    │  ├─ 헤더 추출/신규생성  │
                    │  ├─ JMX invoke ──────────────▶ TraceRegistry MBean
                    │  │   registerTrace()   │       (agent classloader,
                    │  ├─ X-Trace-Id 헤더    │        JVM 전역 MBeanServer)
                    │  └─ HTML 주입          │              │
                    └──────────────────────┘              │
                                                           ▼
                    ┌──────────────────────┐   AgentThreadCollector
                    │ AgentHttpServer       │   readLocalTraceRegistry()
                    │ /agent/requests       │◀──────────────┘
                    │ /agent/threads        │
                    └──────────────────────┘
                              │
                              ▼
              Java APM Dashboard — HTTP 요청 테이블 · 스레드 목록에 Trace ID 배지 표시
```

> **두 JAR를 분리하는 이유**  
> `-javaagent:` 로 로드된 JAR는 JVM의 **system classloader**(AppClassLoader)에서 실행됩니다.  
> system classloader는 `jakarta.servlet.Filter` 에 접근할 수 없으므로,  
> servlet API 에 의존하는 `TraceIdFilter` / `HtmlInjectionWrapper` 는 별도  
> **integration JAR** 로 분리하여 webapp classloader(WEB-INF/lib) 에서 로드합니다.  
> `TraceIdFilter` → `TraceRegistry` 통신은 JVM 전역 MBeanServer 를 통한 JMX invoke 로 처리합니다.

### 지원하는 트레이스 헤더 (우선순위 순)

| 헤더 | 포맷 | 비고 |
|---|---|---|
| `traceparent` | `00-{32hex}-{16hex}-{flags}` | W3C Trace Context |
| `x-datadog-trace-id` | 64-bit 십진수 | Datadog APM |
| `x-b3-traceid` | 16 or 32 hex | Zipkin / Jaeger B3 |
| `x-trace-id` | 임의 문자열 | 커스텀 |
| `x-request-id` | 임의 문자열 | Nginx, AWS ALB |
| `x-amzn-trace-id` | `Root=1-...` | AWS X-Ray |

수신 헤더가 없으면 128-bit UUID 형식의 ID를 자동 생성합니다.

---

## 1. JAR 파일 빌드

```bash
mvn clean package -f /path/to/java-monitor

# 생성 파일:
# target/java-monitor-{version}-agent.jar        ← 대상 JVM 에 -javaagent: 로 부착
# target/java-monitor-{version}-integration.jar  ← 웹앱 WEB-INF/lib 에 배치
```

---

## 2. JAR 배포

두 JAR를 대상 서버에 복사합니다.

```
/opt/agent/
  java-monitor-{version}-agent.jar        ← JVM 시작 옵션에 경로 지정
  java-monitor-{version}-integration.jar  ← 각 웹앱의 WEB-INF/lib/ 에 복사
```

> **agent JAR vs integration JAR 역할 요약**
>
> | JAR | 포함 클래스 | 로드 위치 |
> |---|---|---|
> | `agent.jar` | `TraceRegistry`, `AgentThreadCollector`, `AgentHttpServer`, ... | `-javaagent:` → system classloader |
> | `integration.jar` | `TraceIdFilter`, `HtmlInjectionWrapper`, `TraceRegistry` | `WEB-INF/lib` → webapp classloader |

---

## 3. 통합 방법

### 3-A. Tomcat 독립 실행 (standalone)

#### JVM 옵션 (`setenv.sh` / `setenv.bat`)

```bash
# setenv.sh
JAVA_OPTS="$JAVA_OPTS -javaagent:/opt/agent/java-monitor-agent.jar=port=7070"
```

```bat
rem setenv.bat
set JAVA_OPTS=%JAVA_OPTS% -javaagent:C:\agent\java-monitor-agent.jar=port=7070
```

#### 필터 등록 (`WEB-INF/web.xml`)

```xml
<filter>
    <filter-name>TraceIdFilter</filter-name>
    <filter-class>com.monosun.monitor.trace.TraceIdFilter</filter-class>
    <!-- HTML 응답에 trace ID 주입 여부 (기본 true) -->
    <init-param>
        <param-name>injectHtml</param-name>
        <param-value>true</param-value>
    </init-param>
</filter>
<filter-mapping>
    <filter-name>TraceIdFilter</filter-name>
    <url-pattern>/*</url-pattern>
</filter-mapping>
```

---

### 3-B. Spring Boot (내장 Tomcat)

#### JVM 옵션

```bash
java -javaagent:/opt/agent/java-monitor-agent.jar=port=7070 \
     -jar myapp.jar
```

#### 필터 빈 등록 (`@Configuration`)

```java
import com.monosun.monitor.trace.TraceIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApmConfig {

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceFilter() {
        FilterRegistrationBean<TraceIdFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new TraceIdFilter());
        reg.addUrlPatterns("/*");
        reg.addInitParameter("injectHtml", "true");
        reg.setOrder(1);  // 가장 먼저 실행
        return reg;
    }
}
```

또는 `application.properties` 방식이 없으므로 반드시 `@Bean` 으로 등록합니다.

---

### 3-C. Quarkus (RESTEasy)

#### JVM 옵션

```bash
java -javaagent:/opt/agent/java-monitor-agent.jar=port=7070 \
     -jar quarkus-app/quarkus-run.jar
```

#### JAX-RS ContainerRequestFilter / ContainerResponseFilter

Quarkus는 `jakarta.servlet.Filter` 를 기본 지원하지 않으므로 JAX-RS 필터를 사용합니다.

```java
import com.monosun.monitor.trace.TraceRegistry;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.UUID;

@Provider
@Priority(Priorities.HEADER_DECORATOR)
public class TraceIdContainerFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final String TRACE_KEY = "apm.traceId";

    @Override
    public void filter(ContainerRequestContext req) throws IOException {
        String traceId = extractTraceId(req);
        if (traceId == null) traceId = generateTraceId();
        req.setProperty(TRACE_KEY, traceId);
        long threadId = Thread.currentThread().getId();
        TraceRegistry.register(threadId, traceId);
    }

    @Override
    public void filter(ContainerRequestContext req, ContainerResponseContext resp) throws IOException {
        Object traceId = req.getProperty(TRACE_KEY);
        if (traceId != null) {
            resp.getHeaders().add("X-Trace-Id", traceId.toString());
            resp.getHeaders().add("X-B3-TraceId", traceId.toString());
        }
        TraceRegistry.deregister(Thread.currentThread().getId());
    }

    private String extractTraceId(ContainerRequestContext req) {
        // traceparent: 00-{traceId}-{spanId}-{flags}
        String tp = req.getHeaderString("traceparent");
        if (tp != null) {
            String[] p = tp.split("-");
            if (p.length >= 2) return p[1];
        }
        String[] hdrs = {"x-datadog-trace-id","x-b3-traceid","x-trace-id","x-request-id"};
        for (String h : hdrs) {
            String v = req.getHeaderString(h);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private String generateTraceId() {
        long hi = System.nanoTime() ^ (Thread.currentThread().getId() * 0x9E3779B97F4A7C15L);
        long lo = System.currentTimeMillis() ^ UUID.randomUUID().getMostSignificantBits();
        return String.format("%016x%016x", hi, lo);
    }
}
```

---

### 3-D. WildFly / JBoss EAP

```xml
<!-- WEB-INF/web.xml — 3-A와 동일 -->
```

JVM 옵션은 `standalone.xml` 또는 `domain.xml` 의 `<jvm-options>` 에 추가합니다.

```xml
<jvm-options>
    <option value="-javaagent:/opt/agent/java-monitor-agent.jar=port=7070"/>
</jvm-options>
```

---

## 4. 로그 MDC 연동 (선택)

애플리케이션 로그에 traceId를 자동 포함하려면 필터 안에서 MDC에 등록합니다.

### Logback / Log4j2

```java
// TraceIdFilter 를 상속하거나 별도 필터로 구현
import org.slf4j.MDC;

public class MdcTraceFilter extends TraceIdFilter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        // 부모 필터 실행 전에 MDC 설정 (traceId는 부모가 ThreadLocal에 저장)
        super.doFilter(req, resp, chain);
    }
}
```

또는 `TraceIdFilter` 직후에 실행되는 별도 필터를 추가합니다.

```java
@Component
@Order(2)
public class MdcFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        // X-Trace-Id 헤더는 TraceIdFilter 가 이미 설정한 상태
        String traceId = ((HttpServletResponse) resp).getHeader("X-Trace-Id");
        if (traceId != null) MDC.put("traceId", traceId);
        try {
            chain.doFilter(req, resp);
        } finally {
            MDC.remove("traceId");
        }
    }
}
```

**logback.xml 패턴 예시:**

```xml
<pattern>%d{HH:mm:ss} [%X{traceId}] %-5level %logger{36} - %msg%n</pattern>
```

---

## 5. 대시보드에서 확인

1. Java APM Dashboard 실행 (`java -jar java-monitor-{version}.jar`)
2. 브라우저에서 `http://localhost:8080` 접속
3. **Agent** 또는 **Remote JMX** 섹션 → **활성 HTTP 요청** 테이블의 **Trace ID** 컬럼 확인
4. 스레드 목록에서도 활성 요청 스레드에 **녹색 배지**로 traceId 앞 8자리 표시
5. 요청 행 클릭 → **상세 모달**에서 전체 traceId (128-bit hex) 확인

---

## 6. HTML 주입 확인 (브라우저)

`injectHtml=true` 설정 시 응답 HTML에 다음이 자동 주입됩니다.

```html
<head>
  ...
  <meta name="trace-id" content="3a7f2b1e0d4c5a69...">
  <script>window.__APM_TRACE_ID='3a7f2b1e0d4c5a69...';</script>
</head>
```

브라우저 콘솔에서:

```javascript
window.__APM_TRACE_ID  // "3a7f2b1e0d4c5a69a8b7c6d5e4f30211"
```

---

## 7. 주의 사항

| 항목 | 내용 |
|---|---|
| **JAR 분리 필수** | `TraceIdFilter`가 포함된 **integration JAR** 를 `WEB-INF/lib` 에 배치. agent JAR 를 WEB-INF/lib 에 넣으면 안 됨 — system classloader 와 webapp classloader 간 클래스 중복 충돌 발생 |
| **agent JAR 에 servlet API 없음** | `-javaagent:` 로 로드되는 agent JAR 는 `jakarta.servlet.Filter` 에 접근 불가. integration JAR 로 분리한 이유 |
| 내장 Tomcat (Spring Boot) | `-javaagent` 없이 integration JAR 만 추가해도 `TraceIdFilter` 단독 사용 가능. 대시보드와 traceId 연동은 agent 필요 |
| HTML 주입 성능 | 응답을 전체 버퍼링하므로 매우 큰 HTML (수 MB)은 `injectHtml=false` 권장 |
| 비 HTML 응답 | `Accept` 헤더에 `text/html` 이 없으면 HTML 주입 건너뜀 |
| 멀티 컨텍스트 | 동일 JVM에 여러 webapp 배포 시 `TraceRegistry` MBean은 JVM 전역 단 1개 등록. 모든 webapp 의 traceId 를 하나의 registry 에서 관리 |
| Java 버전 | Java 11 이상 필요 (Java 21 권장) |
| Tomcat 버전 | Tomcat 10.x (Jakarta EE 9+) 이상. Tomcat 9.x 는 `javax.servlet` 패키지 사용 — 별도 빌드 필요 |
