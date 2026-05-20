package com.monosun.monitor.integration.servlet;

import com.monosun.monitor.core.Span;
import com.monosun.monitor.core.TraceContext;
import com.monosun.monitor.core.TransactionTracer;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * HTTP 요청 단위 추적 Servlet Filter.
 *
 * 지원 프레임워크:
 *   - Jakarta EE (WildFly, Payara, GlassFish)
 *   - Tomcat / Jetty (임베디드 포함)
 *   - Quarkus (Servlet 확장 사용 시)
 *   - Helidon SE (Servlet 사용 시)
 *
 * web.xml 등록 예:
 *   <filter>
 *     <filter-name>monitoringFilter</filter-name>
 *     <filter-class>com.monosun.monitor.integration.servlet.MonitoringFilter</filter-class>
 *   </filter>
 *   <filter-mapping>
 *     <filter-name>monitoringFilter</filter-name>
 *     <url-pattern>/*</url-pattern>
 *   </filter-mapping>
 *
 * Jakarta EE CDI 등록 예:
 *   @WebFilter("/*")
 *   public class MonitoringFilter extends com.monosun.monitor.integration.servlet.MonitoringFilter {}
 */
public class MonitoringFilter implements Filter {

    private static final String TRACE_ID_HEADER  = "X-Trace-Id";
    private static final String SPAN_ID_HEADER   = "X-Span-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpReq)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletResponse httpResp = (HttpServletResponse) response;

        // 외부 트레이스 ID 전파 지원 (분산 추적)
        String incomingTraceId = httpReq.getHeader(TRACE_ID_HEADER);
        TraceContext ctx = (incomingTraceId != null && !incomingTraceId.isBlank())
            ? TraceContext.startTrace(incomingTraceId)
            : TraceContext.startTrace();

        String operationName = httpReq.getMethod() + " " + httpReq.getRequestURI();
        Span span = ctx.startSpan(operationName);
        span.tag("http.method", httpReq.getMethod());
        span.tag("http.url",    httpReq.getRequestURI());
        span.tag("http.query",  httpReq.getQueryString() != null ? httpReq.getQueryString() : "");
        span.tag("http.client", httpReq.getRemoteAddr());

        // 응답 헤더에 추적 ID 전파
        httpResp.setHeader(TRACE_ID_HEADER, ctx.getTraceId());
        httpResp.setHeader(SPAN_ID_HEADER,  span.getSpanId());

        try {
            chain.doFilter(request, response);
            span.tag("http.status", String.valueOf(httpResp.getStatus()));
            if (httpResp.getStatus() >= 500) {
                span.tag("error", "true");
                TransactionTracer.getInstance().finish(span,
                    new RuntimeException("HTTP " + httpResp.getStatus()));
            } else {
                TransactionTracer.getInstance().finish(span);
            }
        } catch (Exception e) {
            span.tag("http.status", "500");
            TransactionTracer.getInstance().finish(span, e);
            throw e;
        } finally {
            TraceContext.clear();
        }
    }
}
