package com.monosun.monitor.trace;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.IOException;
import java.lang.management.ManagementFactory;

/**
 * HTTP 요청마다 Trace ID를 생성·전파하는 경량 서블릿 필터.
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  지원 입력 헤더 (우선순위 순)                                     │
 * │  1. traceparent         — W3C Trace Context (표준)              │
 * │  2. x-datadog-trace-id  — Datadog APM                          │
 * │  3. x-b3-traceid        — Zipkin / Jaeger B3                   │
 * │  4. x-trace-id          — 범용                                  │
 * │  5. x-request-id        — Nginx/LB 등 범용 폴백                  │
 * │  6. x-amzn-trace-id     — AWS X-Ray                            │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * 동작:
 *   1. 수신 헤더에서 traceId 추출 (없으면 128비트 hex 신규 생성)
 *   2. 응답 헤더에 X-Trace-Id / X-B3-TraceId 추가
 *   3. TraceRegistry 에 threadId → traceId 등록 (JMX 경유로 APM 대시보드가 읽음)
 *   4. HTML 응답인 경우 &lt;/head&gt; 직전에 meta + script 주입
 *   5. 요청 완료 후 TraceRegistry 에서 제거
 *
 * ── web.xml 등록 ──────────────────────────────────────────────────
 * {@code
 * <filter>
 *   <filter-name>traceIdFilter</filter-name>
 *   <filter-class>com.monosun.monitor.trace.TraceIdFilter</filter-class>
 *   <async-supported>true</async-supported>  <!-- 비동기 서블릿(startAsync) 사용 시 필수 -->
 *   <init-param>
 *     <param-name>injectHtml</param-name>
 *     <param-value>true</param-value>
 *   </init-param>
 * </filter>
 * <filter-mapping>
 *   <filter-name>traceIdFilter</filter-name>
 *   <url-pattern>/*</url-pattern>
 * </filter-mapping>
 * }
 *
 * ── Spring Boot 등록 ──────────────────────────────────────────────
 * {@code
 * @Bean
 * public FilterRegistrationBean<TraceIdFilter> traceFilter() {
 *     FilterRegistrationBean<TraceIdFilter> bean = new FilterRegistrationBean<>(new TraceIdFilter());
 *     bean.addUrlPatterns("/*");
 *     bean.setAsyncSupported(true);  // 비동기 서블릿(startAsync) 사용 시 필수
 *     bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
 *     return bean;
 * }
 * }
 */
public class TraceIdFilter implements Filter {

    private static final String TRACE_REGISTRY_MBEAN = "com.monosun.monitor:type=TraceRegistry";

    // ── 수신 헤더 (우선순위 순) ────────────────────────────────────────────────
    private static final String[] INCOMING = {
        "traceparent",
        "x-datadog-trace-id",
        "x-b3-traceid",
        "x-trace-id",
        "x-request-id",
        "x-amzn-trace-id"
    };

    public static final String RESP_HEADER     = "X-Trace-Id";
    public static final String RESP_HEADER_B3  = "X-B3-TraceId";
    /** HttpServletRequest attribute key — 필터 체인 내에서 traceId 접근 시 사용 */
    public static final String REQUEST_ATTR    = "com.monosun.monitor.traceId";

    private boolean injectHtml = true;

    @Override
    public void init(FilterConfig cfg) {
        String v = cfg.getInitParameter("injectHtml");
        if (v != null) injectHtml = Boolean.parseBoolean(v);
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        if (!(req instanceof HttpServletRequest)) { chain.doFilter(req, res); return; }

        HttpServletRequest  request  = (HttpServletRequest)  req;
        HttpServletResponse response = (HttpServletResponse) res;

        // 1. Extract or generate
        String traceId = extractTraceId(request);
        if (traceId == null) traceId = generate();

        // 2. Store in request attr + JMX registry (agent classloader 와 공유)
        request.setAttribute(REQUEST_ATTR, traceId);
        long tid = Thread.currentThread().getId();
        jmxRegister(tid, traceId);

        // 3. Propagate to response headers
        response.setHeader(RESP_HEADER,    traceId);
        response.setHeader(RESP_HEADER_B3, traceId);

        // 4. Optionally inject into HTML (async 요청에는 주입 건너뜀)
        String accept = request.getHeader("Accept");
        boolean wantsHtml = injectHtml
            && accept != null
            && (accept.contains("text/html") || accept.contains("application/xhtml"));

        if (wantsHtml) {
            HtmlInjectionWrapper wrapped = new HtmlInjectionWrapper(response, traceId);
            try {
                chain.doFilter(request, wrapped);
            } finally {
                if (request.isAsyncStarted()) {
                    // async 완료 시점에 주입 및 cleanup
                    final long asyncTid = tid;
                    request.getAsyncContext().addListener(new AsyncListener() {
                        @Override public void onComplete(AsyncEvent e) throws IOException {
                            wrapped.finish();
                            jmxDeregister(asyncTid);
                        }
                        @Override public void onTimeout(AsyncEvent e)    { jmxDeregister(asyncTid); }
                        @Override public void onError(AsyncEvent e)      { jmxDeregister(asyncTid); }
                        @Override public void onStartAsync(AsyncEvent e) {}
                    });
                } else {
                    wrapped.finish();
                    jmxDeregister(tid);
                }
            }
        } else {
            try {
                chain.doFilter(request, response);
            } finally {
                if (request.isAsyncStarted()) {
                    // async 완료 시점에 cleanup
                    final long asyncTid = tid;
                    request.getAsyncContext().addListener(new AsyncListener() {
                        @Override public void onComplete(AsyncEvent e)   { jmxDeregister(asyncTid); }
                        @Override public void onTimeout(AsyncEvent e)    { jmxDeregister(asyncTid); }
                        @Override public void onError(AsyncEvent e)      { jmxDeregister(asyncTid); }
                        @Override public void onStartAsync(AsyncEvent e) {}
                    });
                } else {
                    jmxDeregister(tid);
                }
            }
        }
    }

    // ── JMX registry helpers (에이전트 클래스로더와 직접 의존 없이 통신) ──────────

    private void jmxRegister(long threadId, String traceId) {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName on = new ObjectName(TRACE_REGISTRY_MBEAN);
            if (mbs.isRegistered(on)) {
                mbs.invoke(on, "registerTrace",
                    new Object[]{threadId, traceId},
                    new String[]{"long", "java.lang.String"});
            }
        } catch (Exception ignored) {}
    }

    private void jmxDeregister(long threadId) {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName on = new ObjectName(TRACE_REGISTRY_MBEAN);
            if (mbs.isRegistered(on)) {
                mbs.invoke(on, "deregisterTrace",
                    new Object[]{threadId},
                    new String[]{"long"});
            }
        } catch (Exception ignored) {}
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String extractTraceId(HttpServletRequest req) {
        for (String h : INCOMING) {
            String v = req.getHeader(h);
            if (v == null || v.isBlank()) continue;

            switch (h.toLowerCase()) {
                case "traceparent" -> {
                    // "00-{traceId 32hex}-{spanId 16hex}-{flags}"
                    String[] p = v.split("-", 4);
                    if (p.length >= 2 && !p[1].isBlank()) return p[1];
                }
                case "x-datadog-trace-id" -> {
                    // Datadog: 64비트 십진수 → hex 변환
                    try { return Long.toHexString(Long.parseUnsignedLong(v.trim())); }
                    catch (NumberFormatException e) { return v.trim(); }
                }
                case "x-amzn-trace-id" -> {
                    // "Root=1-xxx-yyy;Self=..."
                    for (String seg : v.split(";")) {
                        if (seg.startsWith("Root=")) return seg.substring(5).trim();
                    }
                }
                default -> { return v.trim(); }
            }
        }
        return null;
    }

    /**
     * 128비트 hex Trace ID 생성 (W3C traceparent / Datadog 128비트 모드 호환).
     * 형식: {nanoTime 16hex}{threadId+millis xor 16hex}
     */
    private static String generate() {
        long hi = System.nanoTime()  ^ (Thread.currentThread().getId() * 0x9E3779B97F4A7C15L);
        long lo = System.currentTimeMillis() ^ (long)(Math.random() * Long.MAX_VALUE);
        return String.format("%016x%016x", hi, lo);
    }
}
