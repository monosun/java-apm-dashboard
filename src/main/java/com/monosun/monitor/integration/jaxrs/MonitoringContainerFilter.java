package com.monosun.monitor.integration.jaxrs;

import com.monosun.monitor.core.Span;
import com.monosun.monitor.core.TraceContext;
import com.monosun.monitor.core.TransactionTracer;
import javax.ws.rs.container.*;
import javax.ws.rs.ext.Provider;

import java.io.IOException;

/**
 * JAX-RS 요청/응답 추적 필터.
 *
 * 지원 프레임워크:
 *   - Jersey (Jakarta EE 10+)
 *   - RESTEasy (WildFly, Quarkus)
 *   - Apache CXF
 *   - Quarkus REST (RESTEasy Reactive)
 *   - Helidon MP
 *
 * @Provider 어노테이션으로 자동 등록됩니다.
 * Jersey/RESTEasy 수동 등록:
 *   resourceConfig.register(MonitoringContainerFilter.class);
 */
@Provider
@PreMatching
public class MonitoringContainerFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final String SPAN_KEY         = "monitor.span";
    private static final String CONTEXT_KEY      = "monitor.ctx";
    private static final String TRACE_ID_HEADER  = "X-Trace-Id";
    private static final String SPAN_ID_HEADER   = "X-Span-Id";

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String incomingTraceId = requestContext.getHeaderString(TRACE_ID_HEADER);
        TraceContext ctx = (incomingTraceId != null && !incomingTraceId.isBlank())
            ? TraceContext.startTrace(incomingTraceId)
            : TraceContext.startTrace();

        String operationName = requestContext.getMethod() + " "
            + requestContext.getUriInfo().getPath();
        Span span = ctx.startSpan(operationName);
        span.tag("http.method", requestContext.getMethod());
        span.tag("http.path",   requestContext.getUriInfo().getPath());
        span.tag("http.query",  String.valueOf(requestContext.getUriInfo().getQueryParameters()));

        requestContext.setProperty(SPAN_KEY,    span);
        requestContext.setProperty(CONTEXT_KEY, ctx);
    }

    @Override
    public void filter(ContainerRequestContext requestContext,
                       ContainerResponseContext responseContext) throws IOException {
        Span span = (Span) requestContext.getProperty(SPAN_KEY);
        if (span == null) return;

        int status = responseContext.getStatus();
        span.tag("http.status", String.valueOf(status));

        responseContext.getHeaders().add(TRACE_ID_HEADER, span.getTraceId());
        responseContext.getHeaders().add(SPAN_ID_HEADER,  span.getSpanId());

        if (status >= 500) {
            TransactionTracer.getInstance().finish(span,
                new RuntimeException("HTTP " + status));
        } else {
            TransactionTracer.getInstance().finish(span);
        }
        TraceContext.clear();
    }
}
