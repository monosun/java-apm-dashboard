# TraceId로 Request 파라미터 / 헤더 정보 조회하기

`TraceIdFilter`가 모든 요청에 traceId를 부여하고 `HttpServletRequest` 속성 및 `TraceRegistry`(JMX)에 등록합니다.
이 문서는 traceId를 기준으로 요청 파라미터와 헤더 정보를 수집·조회하는 패턴을 설명합니다.

---

## 1. 현재 요청의 TraceId 얻기

### 1-1. Servlet / Filter 내부

```java
import com.monosun.monitor.trace.TraceIdFilter;

public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
    HttpServletRequest request = (HttpServletRequest) req;

    // TraceIdFilter가 이미 저장해 둔 값
    String traceId = (String) request.getAttribute(TraceIdFilter.REQUEST_ATTR);
    // → "a3f1b2c4d5e6f7a8b9c0d1e2f3a4b5c6"
}
```

### 1-2. Spring MVC Controller

```java
import com.monosun.monitor.trace.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;

@RestController
public class OrderController {

    @GetMapping("/orders")
    public ResponseEntity<List<Order>> list(HttpServletRequest request) {
        String traceId = (String) request.getAttribute(TraceIdFilter.REQUEST_ATTR);
        log.info("[{}] 주문 목록 조회", traceId);
        // ...
    }
}
```

### 1-3. Service 레이어 (HttpServletRequest 없이)

`RequestContextHolder`를 사용하면 서비스 레이어에서도 꺼낼 수 있습니다.

```java
import com.monosun.monitor.trace.TraceIdFilter;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public String currentTraceId() {
    ServletRequestAttributes attrs =
        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attrs == null) return null;
    return (String) attrs.getRequest().getAttribute(TraceIdFilter.REQUEST_ATTR);
}
```

---

## 2. Request 파라미터 / 헤더 수집 패턴

### 2-1. MDC 기반 (로그 자동 삽입 — 가장 권장)

SLF4J `MDC`에 traceId를 넣으면 Logback / Log4j2 패턴에서 자동으로 출력됩니다.

#### Filter 구현

```java
import com.monosun.monitor.trace.TraceIdFilter;
import org.slf4j.MDC;

@WebFilter("/*")
public class MdcTraceFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;

        // TraceIdFilter가 먼저 실행된 뒤 이 필터가 실행된다고 가정
        // (web.xml / FilterRegistrationBean order 에서 TraceIdFilter 를 더 높은 우선순위로 등록)
        String traceId = (String) request.getAttribute(TraceIdFilter.REQUEST_ATTR);

        try {
            MDC.put("traceId", traceId);
            MDC.put("method",  request.getMethod());
            MDC.put("uri",     request.getRequestURI());
            chain.doFilter(req, res);
        } finally {
            MDC.clear();
        }
    }
}
```

#### logback.xml 패턴 예시

```xml
<pattern>%d{HH:mm:ss} [%X{traceId}] %-5level %logger{36} - %msg%n</pattern>
```

#### 출력 예시

```
14:32:01 [a3f1b2c4d5e6f7a8] INFO  c.example.OrderService - 주문 처리 완료
```

---

### 2-2. RequestContext 저장소 (traceId → 요청 정보 맵)

특정 traceId로 나중에 파라미터·헤더를 조회해야 할 때 사용합니다.

#### RequestInfo (VO)

```java
public record RequestInfo(
    String traceId,
    String method,
    String uri,
    Map<String, String>       headers,
    Map<String, List<String>> params,
    long   timestamp
) {}
```

#### RequestContextStore

```java
import java.util.concurrent.ConcurrentHashMap;

public final class RequestContextStore {

    private static final ConcurrentHashMap<String, RequestInfo> STORE = new ConcurrentHashMap<>(256);

    public static void put(String traceId, RequestInfo info) {
        STORE.put(traceId, info);
    }

    /** traceId 로 요청 정보 조회 */
    public static RequestInfo get(String traceId) {
        return STORE.get(traceId);
    }

    public static void remove(String traceId) {
        STORE.remove(traceId);
    }
}
```

#### 수집 Filter

```java
import com.monosun.monitor.trace.TraceIdFilter;

@WebFilter("/*")
public class RequestContextCaptureFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        String traceId = (String) request.getAttribute(TraceIdFilter.REQUEST_ATTR);

        if (traceId != null) {
            RequestContextStore.put(traceId, buildInfo(traceId, request));
        }

        try {
            chain.doFilter(req, res);
        } finally {
            if (traceId != null) {
                RequestContextStore.remove(traceId);   // 요청 완료 후 메모리 해제
            }
        }
    }

    private RequestInfo buildInfo(String traceId, HttpServletRequest req) {
        // 헤더 수집
        Map<String, String> headers = new LinkedHashMap<>();
        Collections.list(req.getHeaderNames())
                   .forEach(name -> headers.put(name, req.getHeader(name)));

        // 파라미터 수집
        Map<String, List<String>> params = new LinkedHashMap<>();
        req.getParameterMap()
           .forEach((k, v) -> params.put(k, Arrays.asList(v)));

        return new RequestInfo(
            traceId,
            req.getMethod(),
            req.getRequestURI(),
            Collections.unmodifiableMap(headers),
            Collections.unmodifiableMap(params),
            System.currentTimeMillis()
        );
    }
}
```

#### 조회 예시

```java
// 같은 요청 스레드 내에서
RequestInfo info = RequestContextStore.get(traceId);
System.out.println(info.params());   // {page=[1], size=[20]}
System.out.println(info.headers());  // {authorization=Bearer ..., accept=application/json}
```

---

### 2-3. TraceRegistry로 traceId ↔ threadId 역방향 조회

에이전트 또는 외부에서 threadId를 알고 있을 때 traceId를 역산합니다.

```java
import com.monosun.monitor.trace.TraceRegistry;

// threadId → traceId
String traceId = TraceRegistry.get(Thread.currentThread().getId());

// traceId로 RequestInfo 조회 (2-2와 연계)
RequestInfo info = RequestContextStore.get(traceId);
```

---

## 3. Spring Boot 완전 연동 예제

### web.xml 없이 Bean 등록 (필터 순서 중요)

```java
@Configuration
public class TraceFilterConfig {

    /** 1순위: traceId 생성 */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> bean =
            new FilterRegistrationBean<>(new TraceIdFilter());
        bean.addUrlPatterns("/*");
        bean.setAsyncSupported(true);
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);          // order = -2147483648
        return bean;
    }

    /** 2순위: MDC + RequestContext 수집 (traceId 생성 이후 실행) */
    @Bean
    public FilterRegistrationBean<RequestContextCaptureFilter> contextFilter() {
        FilterRegistrationBean<RequestContextCaptureFilter> bean =
            new FilterRegistrationBean<>(new RequestContextCaptureFilter());
        bean.addUrlPatterns("/*");
        bean.setAsyncSupported(true);
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);      // order = -2147483647
        return bean;
    }
}
```

### Controller에서 파라미터·헤더 전체 조회

```java
@RestController
@RequestMapping("/debug")
public class DebugController {

    @GetMapping("/trace/{traceId}")
    public ResponseEntity<RequestInfo> traceInfo(@PathVariable String traceId) {
        RequestInfo info = RequestContextStore.get(traceId);
        if (info == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(info);
    }
}
```

### 응답 예시

```json
GET /debug/trace/a3f1b2c4d5e6f7a8b9c0d1e2f3a4b5c6

{
  "traceId": "a3f1b2c4d5e6f7a8b9c0d1e2f3a4b5c6",
  "method":  "GET",
  "uri":     "/api/orders",
  "headers": {
    "authorization": "Bearer eyJhbGc...",
    "accept":        "application/json",
    "x-user-id":     "user-42"
  },
  "params": {
    "page": ["1"],
    "size": ["20"],
    "status": ["PENDING"]
  },
  "timestamp": 1748132521000
}
```

---

## 4. 클라이언트(브라우드) ↔ 서버 traceId 연계

`TraceIdFilter`는 HTML 응답 `</head>` 직전에 스니펫을 자동 삽입합니다.

```html
<meta name="trace-id" content="a3f1b2c4d5e6f7a8">
<script>window.__APM_TRACE_ID = 'a3f1b2c4d5e6f7a8';</script>
```

프론트엔드에서 API 호출 시 이 값을 헤더로 전달하면 서버와 브라우저 로그가 연결됩니다.

```js
// fetch 예시
const traceId = window.__APM_TRACE_ID
             || document.querySelector('meta[name="trace-id"]')?.content;

fetch('/api/orders', {
  headers: {
    'X-Trace-Id': traceId,   // 서버가 INCOMING 헤더로 인식해 동일 traceId 유지
    'Content-Type': 'application/json'
  }
});
```

---

## 5. 핵심 상수 / API 요약

| 항목 | 값 |
|------|-----|
| Request attribute key | `com.monosun.monitor.traceId` (`TraceIdFilter.REQUEST_ATTR`) |
| Response header | `X-Trace-Id`, `X-B3-TraceId` |
| JMX ObjectName | `com.monosun.monitor:type=TraceRegistry` |
| JMX Attribute | `ActiveTraces` — `{"threadId":"traceId", ...}` JSON |
| 직접 API | `TraceRegistry.get(threadId)` — 동일 JVM·클래스로더 내 |
