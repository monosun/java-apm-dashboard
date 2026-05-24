package com.monosun.monitor.trace;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import java.util.Enumeration;

/**
 * 요청 파라미터·헤더를 {@link RequestContextStore}에 traceId 키로 저장하는 서블릿 필터.
 *
 * {@link TraceIdFilter}가 먼저 실행된 뒤 이 필터가 실행되어야 합니다.
 *
 * ── web.xml 등록 ──────────────────────────────────────────────────
 * {@code
 * <!-- TraceIdFilter 다음에 선언 -->
 * <filter>
 *   <filter-name>requestContextCaptureFilter</filter-name>
 *   <filter-class>com.monosun.monitor.trace.RequestContextCaptureFilter</filter-class>
 *   <async-supported>true</async-supported>
 * </filter>
 * <filter-mapping>
 *   <filter-name>requestContextCaptureFilter</filter-name>
 *   <url-pattern>/*</url-pattern>
 * </filter-mapping>
 * }
 *
 * ── Spring Boot 등록 ──────────────────────────────────────────────
 * {@code
 * @Bean
 * public FilterRegistrationBean<RequestContextCaptureFilter> requestContextFilter() {
 *     FilterRegistrationBean<RequestContextCaptureFilter> bean =
 *         new FilterRegistrationBean<>(new RequestContextCaptureFilter());
 *     bean.addUrlPatterns("/*");
 *     bean.setAsyncSupported(true);
 *     bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);   // TraceIdFilter(HIGHEST_PRECEDENCE) 다음
 *     return bean;
 * }
 * }
 *
 * ── 조회 예시 ─────────────────────────────────────────────────────
 * {@code
 * String traceId = (String) request.getAttribute(TraceIdFilter.REQUEST_ATTR);
 * RequestInfo info = RequestContextStore.get(traceId);
 * info.params()   // {page=[1], size=[20]}
 * info.headers()  // {authorization=Bearer ..., x-user-id=user-42}
 * }
 */
public class RequestContextCaptureFilter implements Filter {

    /** 수집 대상에서 제외할 헤더 (소문자 비교). 민감 정보 보호. */
    private static final Set<String> SKIP_HEADERS = Set.of(
        "cookie", "authorization"
    );

    /** init-param: skipHeaders=cookie,authorization  (쉼표 구분, 소문자) */
    private Set<String> skipHeaders = SKIP_HEADERS;

    @Override
    public void init(FilterConfig cfg) {
        String param = cfg.getInitParameter("skipHeaders");
        if (param != null && !param.isBlank()) {
            Set<String> custom = new HashSet<>();
            for (String h : param.split(",")) {
                String trimmed = h.trim().toLowerCase();
                if (!trimmed.isEmpty()) custom.add(trimmed);
            }
            skipHeaders = Collections.unmodifiableSet(custom);
        }
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        if (!(req instanceof HttpServletRequest)) {
            chain.doFilter(req, res);
            return;
        }

        HttpServletRequest request = (HttpServletRequest) req;
        String traceId = (String) request.getAttribute(TraceIdFilter.REQUEST_ATTR);

        if (traceId != null) {
            RequestContextStore.put(traceId, buildInfo(traceId, request));
            TraceRegistry.registerContext(traceId, buildContextJson(traceId, request));
        }

        try {
            chain.doFilter(req, res);
        } finally {
            if (traceId != null) {
                if (request.isAsyncStarted()) {
                    // async 완료 시점에 제거
                    final String asyncTraceId = traceId;
                    request.getAsyncContext().addListener(new AsyncListener() {
                        @Override public void onComplete(AsyncEvent e)   { cleanup(asyncTraceId); }
                        @Override public void onTimeout(AsyncEvent e)    { cleanup(asyncTraceId); }
                        @Override public void onError(AsyncEvent e)      { cleanup(asyncTraceId); }
                        @Override public void onStartAsync(AsyncEvent e) {}
                    });
                } else {
                    cleanup(traceId);
                }
            }
        }
    }

    private void cleanup(String traceId) {
        RequestContextStore.remove(traceId);
        TraceRegistry.deregisterContext(traceId);
    }

    private String buildContextJson(String traceId, HttpServletRequest req) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"traceId\":\"").append(ej(traceId)).append("\",");
        sb.append("\"method\":\"").append(ej(req.getMethod())).append("\",");
        sb.append("\"uri\":\"").append(ej(req.getRequestURI())).append("\",");
        String qs = req.getQueryString();
        sb.append("\"queryString\":").append(qs != null ? "\"" + ej(qs) + "\"" : "null").append(',');
        sb.append("\"remoteAddr\":\"").append(ej(req.getRemoteAddr())).append("\",");
        sb.append("\"headers\":{");
        boolean first = true;
        Enumeration<String> hn = req.getHeaderNames();
        if (hn != null) {
            while (hn.hasMoreElements()) {
                String name = hn.nextElement();
                if (skipHeaders.contains(name.toLowerCase())) continue;
                if (!first) sb.append(',');
                sb.append('"').append(ej(name)).append("\":\"").append(ej(req.getHeader(name))).append('"');
                first = false;
            }
        }
        sb.append("},\"params\":{");
        first = true;
        for (Map.Entry<String, String[]> e : req.getParameterMap().entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(ej(e.getKey())).append("\":[");
            String[] vals = e.getValue();
            for (int i = 0; i < vals.length; i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(ej(vals[i])).append('"');
            }
            sb.append(']');
            first = false;
        }
        sb.append("},\"capturedAt\":").append(System.currentTimeMillis()).append('}');
        return sb.toString();
    }

    private static String ej(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private RequestInfo buildInfo(String traceId, HttpServletRequest req) {
        Map<String, String> headers = new LinkedHashMap<>();
        Collections.list(req.getHeaderNames()).forEach(name -> {
            if (!skipHeaders.contains(name.toLowerCase())) {
                headers.put(name, req.getHeader(name));
            }
        });

        Map<String, List<String>> params = new LinkedHashMap<>();
        req.getParameterMap().forEach((k, v) -> params.put(k, Arrays.asList(v)));

        return new RequestInfo(
            traceId,
            req.getMethod(),
            req.getRequestURI(),
            req.getQueryString(),
            req.getRemoteAddr(),
            Collections.unmodifiableMap(headers),
            Collections.unmodifiableMap(params),
            System.currentTimeMillis()
        );
    }
}
