package com.monosun.monitor.trace;

import java.util.List;
import java.util.Map;

/**
 * 단일 HTTP 요청의 스냅샷.
 * {@link RequestContextCaptureFilter}가 요청 진입 시점에 생성하고
 * {@link RequestContextStore}에 traceId 키로 저장합니다.
 */
public record RequestInfo(
    String traceId,
    String method,
    String uri,
    String queryString,
    String remoteAddr,
    Map<String, String>       headers,
    Map<String, List<String>> params,
    long   capturedAt
) {}
