package com.monosun.monitor.trace;

import javax.management.*;
import java.lang.management.ManagementFactory;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 스레드 ID → Trace ID 매핑 레지스트리.
 *
 * JMX DynamicMBean 으로 자신을 플랫폼 MBeanServer 에 등록합니다.
 * 플랫폼 MBeanServer 는 JVM 전체에서 공유되므로, 웹 앱 클래스로더에서 로드된 이 클래스의
 * MBean 데이터를 에이전트 클래스로더(AgentThreadCollector) 나 원격 JMX 클라이언트가
 * 클래스로더 격리 없이 읽을 수 있습니다.
 *
 * 사용:
 *   TraceRegistry.register(Thread.currentThread().getId(), traceId);   // 요청 시작
 *   TraceRegistry.deregister(Thread.currentThread().getId());           // 요청 종료
 *   String id = TraceRegistry.get(threadId);                           // 동일 JVM 내
 *
 * MBean ObjectName: com.monosun.monitor:type=TraceRegistry
 * MBean Attribute : ActiveTraces  →  JSON {"threadId":"traceId", ...}
 */
public final class TraceRegistry implements DynamicMBean {

    public static final String OBJECT_NAME = "com.monosun.monitor:type=TraceRegistry";

    private static final ConcurrentHashMap<Long, String> MAP = new ConcurrentHashMap<>(64);

    static {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName  on  = new ObjectName(OBJECT_NAME);
            if (!mbs.isRegistered(on)) {
                mbs.registerMBean(new TraceRegistry(), on);
            }
        } catch (Exception ignored) {
            // MBean 등록 실패 시에도 정적 MAP 은 동일 JVM 내에서 사용 가능
        }
    }

    // ── public API ────────────────────────────────────────────────────────────

    public static void register(long threadId, String traceId) {
        if (traceId != null && !traceId.isBlank()) MAP.put(threadId, traceId);
    }

    public static void deregister(long threadId) {
        MAP.remove(threadId);
    }

    /** 동일 JVM 내 직접 읽기 (클래스로더가 같을 때) */
    public static String get(long threadId) {
        return MAP.get(threadId);
    }

    // ── DynamicMBean ──────────────────────────────────────────────────────────

    @Override
    public Object getAttribute(String attribute) {
        if (!"ActiveTraces".equals(attribute)) return null;
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<Long, String> e : MAP.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(e.getKey()).append("\":\"")
              .append(e.getValue().replace("\\", "\\\\").replace("\"", "\\\""))
              .append('"');
            first = false;
        }
        return sb.append('}').toString();
    }

    @Override public void setAttribute(Attribute a) {}
    @Override public AttributeList setAttributes(AttributeList a) { return new AttributeList(); }
    @Override public Object invoke(String op, Object[] p, String[] s) { return null; }

    @Override
    public AttributeList getAttributes(String[] names) {
        AttributeList r = new AttributeList();
        for (String n : names) { Object v = getAttribute(n); if (v != null) r.add(new Attribute(n, v)); }
        return r;
    }

    @Override
    public MBeanInfo getMBeanInfo() {
        return new MBeanInfo(getClass().getName(), "Trace ID Registry per thread",
            new MBeanAttributeInfo[]{
                new MBeanAttributeInfo("ActiveTraces", "java.lang.String",
                    "JSON map {threadId: traceId} for currently active requests",
                    true, false, false)
            }, null, null, null);
    }

    private TraceRegistry() {}
}
