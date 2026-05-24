package com.monosun.monitor.trace;

import java.util.concurrent.ConcurrentHashMap;

/**
 * traceId → {@link RequestInfo} 인메모리 저장소.
 *
 * {@link RequestContextCaptureFilter}가 요청 시작 시 put, 요청 완료 시 remove합니다.
 * 같은 요청 스레드 내 또는 디버그 엔드포인트에서 traceId로 요청 정보를 조회할 때 사용합니다.
 */
public final class RequestContextStore {

    private static final ConcurrentHashMap<String, RequestInfo> STORE = new ConcurrentHashMap<>(256);

    private RequestContextStore() {}

    public static void put(String traceId, RequestInfo info) {
        STORE.put(traceId, info);
    }

    /** traceId로 요청 정보 조회. 요청이 완료되면 null을 반환합니다. */
    public static RequestInfo get(String traceId) {
        return STORE.get(traceId);
    }

    public static void remove(String traceId) {
        STORE.remove(traceId);
    }
}
