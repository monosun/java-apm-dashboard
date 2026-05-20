package com.monosun.monitor.core;

import java.lang.management.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * java.lang.management API를 사용해 JVM 핵심 메트릭을 수집합니다.
 * 외부 라이브러리 없이 순수 JDK만 사용합니다.
 */
public class JvmMetricsCollector {

    private static final Logger LOG = Logger.getLogger(JvmMetricsCollector.class.getName());

    private final MemoryMXBean          memoryMX    = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean          threadMX    = ManagementFactory.getThreadMXBean();
    private final RuntimeMXBean         runtimeMX   = ManagementFactory.getRuntimeMXBean();
    private final ClassLoadingMXBean    classloMX   = ManagementFactory.getClassLoadingMXBean();
    private final CompilationMXBean     compileMX   = ManagementFactory.getCompilationMXBean();
    private final List<GarbageCollectorMXBean> gcMXBeans    = ManagementFactory.getGarbageCollectorMXBeans();
    private final List<MemoryPoolMXBean>       memPoolBeans = ManagementFactory.getMemoryPoolMXBeans();
    private final List<BufferPoolMXBean>       bufPoolBeans = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
    private final com.sun.management.OperatingSystemMXBean osMX;

    public JvmMetricsCollector() {
        com.sun.management.OperatingSystemMXBean tmp = null;
        try {
            tmp = (com.sun.management.OperatingSystemMXBean)
                ManagementFactory.getOperatingSystemMXBean();
        } catch (ClassCastException ignored) {}
        this.osMX = tmp;
    }

    // -------------------------------------------------------------------------
    // 메모리
    // -------------------------------------------------------------------------

    public long heapUsedBytes()      { return memoryMX.getHeapMemoryUsage().getUsed(); }
    public long heapMaxBytes()       { return memoryMX.getHeapMemoryUsage().getMax(); }
    public long heapCommittedBytes() { return memoryMX.getHeapMemoryUsage().getCommitted(); }
    public long nonHeapUsedBytes()   { return memoryMX.getNonHeapMemoryUsage().getUsed(); }

    public double heapUsagePercent() {
        long max = heapMaxBytes();
        return max <= 0 ? 0.0 : (double) heapUsedBytes() / max * 100.0;
    }

    public long metaspaceUsedBytes() {
        return memPoolBeans.stream()
            .filter(p -> p.getName().contains("Metaspace"))
            .mapToLong(p -> p.getUsage().getUsed())
            .findFirst().orElse(-1L);
    }

    // -------------------------------------------------------------------------
    // GC
    // -------------------------------------------------------------------------

    public long gcTotalCount() {
        return gcMXBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
    }

    public long gcTotalTimeMs() {
        return gcMXBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
    }

    /** GC별 상세 정보 반환 */
    public Map<String, long[]> gcDetails() {
        Map<String, long[]> result = new LinkedHashMap<>();
        for (GarbageCollectorMXBean gc : gcMXBeans) {
            result.put(gc.getName(), new long[]{gc.getCollectionCount(), gc.getCollectionTime()});
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // 스레드
    // -------------------------------------------------------------------------

    public int threadCount()        { return threadMX.getThreadCount(); }
    public int daemonThreadCount()  { return threadMX.getDaemonThreadCount(); }
    public int peakThreadCount()    { return threadMX.getPeakThreadCount(); }
    public long totalStartedThreads() { return threadMX.getTotalStartedThreadCount(); }

    public int blockedThreadCount() {
        ThreadInfo[] infos = threadMX.getThreadInfo(threadMX.getAllThreadIds(), 0);
        int count = 0;
        for (ThreadInfo info : infos) {
            if (info != null && info.getThreadState() == Thread.State.BLOCKED) count++;
        }
        return count;
    }

    public int waitingThreadCount() {
        ThreadInfo[] infos = threadMX.getThreadInfo(threadMX.getAllThreadIds(), 0);
        int count = 0;
        for (ThreadInfo info : infos) {
            if (info != null && info.getThreadState() == Thread.State.WAITING) count++;
        }
        return count;
    }

    public int timedWaitingThreadCount() {
        ThreadInfo[] infos = threadMX.getThreadInfo(threadMX.getAllThreadIds(), 0);
        int count = 0;
        for (ThreadInfo info : infos) {
            if (info != null && info.getThreadState() == Thread.State.TIMED_WAITING) count++;
        }
        return count;
    }

    public int runnableThreadCount() {
        ThreadInfo[] infos = threadMX.getThreadInfo(threadMX.getAllThreadIds(), 0);
        int count = 0;
        for (ThreadInfo info : infos) {
            if (info != null && info.getThreadState() == Thread.State.RUNNABLE) count++;
        }
        return count;
    }

    /** 스레드 상세 목록 (BLOCKED→WAITING→TIMED_WAITING→RUNNABLE 순, CPU 내림차순) */
    public List<Map<String, Object>> getThreadList() {
        long[] ids = threadMX.getAllThreadIds();
        if (ids.length > 300) ids = Arrays.copyOf(ids, 300);
        ThreadInfo[] infos = threadMX.getThreadInfo(ids, 5);
        boolean cpuOk = threadMX.isThreadCpuTimeSupported() && threadMX.isThreadCpuTimeEnabled();

        List<Map<String, Object>> result = new ArrayList<>();
        for (ThreadInfo ti : infos) {
            if (ti == null) continue;
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id",       ti.getThreadId());
            t.put("name",     ti.getThreadName());
            t.put("state",    ti.getThreadState().name());
            t.put("daemon",   ti.isDaemon());
            t.put("priority", ti.getPriority());
            if (ti.getLockName()      != null) t.put("lockName",  ti.getLockName());
            if (ti.getLockOwnerName() != null) t.put("lockOwner", ti.getLockOwnerName());
            if (cpuOk) {
                long ns = threadMX.getThreadCpuTime(ti.getThreadId());
                if (ns >= 0) t.put("cpuMs", ns / 1_000_000L);
            }
            StackTraceElement[] stack = ti.getStackTrace();
            if (stack.length > 0) {
                t.put("topFrame", stack[0].toString());
                if (stack.length > 1) t.put("frame2", stack[1].toString());
            }
            result.add(t);
        }
        result.sort((a, b) -> {
            int oa = threadStateOrder(a.get("state").toString());
            int ob = threadStateOrder(b.get("state").toString());
            if (oa != ob) return oa - ob;
            long ca = a.containsKey("cpuMs") ? (Long) a.get("cpuMs") : 0L;
            long cb = b.containsKey("cpuMs") ? (Long) b.get("cpuMs") : 0L;
            return Long.compare(cb, ca);
        });
        return result;
    }

    private static int threadStateOrder(String state) {
        return switch (state) {
            case "BLOCKED"       -> 0;
            case "WAITING"       -> 1;
            case "TIMED_WAITING" -> 2;
            case "RUNNABLE"      -> 3;
            default              -> 4;
        };
    }

    /** 전체 스레드 덤프 (text/plain) */
    public String getThreadDump() {
        long[] ids = threadMX.getAllThreadIds();
        ThreadInfo[] infos = threadMX.getThreadInfo(ids, Integer.MAX_VALUE);
        StringBuilder sb = new StringBuilder();
        sb.append("Full Thread Dump — ").append(java.time.Instant.now()).append("\n\n");
        long[] deadlocked = findDeadlockedThreads();
        java.util.Set<Long> deadSet = new java.util.HashSet<>();
        for (long id : deadlocked) deadSet.add(id);

        for (ThreadInfo ti : infos) {
            if (ti == null) continue;
            sb.append('"').append(ti.getThreadName()).append('"');
            if (ti.isDaemon()) sb.append(" daemon");
            sb.append(" #").append(ti.getThreadId());
            sb.append(" prio=").append(ti.getPriority());
            if (deadSet.contains(ti.getThreadId())) sb.append(" *** DEADLOCKED ***");
            sb.append('\n');
            sb.append("   java.lang.Thread.State: ").append(ti.getThreadState()).append('\n');
            if (ti.getLockName() != null) {
                sb.append("   waiting on <").append(ti.getLockName()).append('>');
                if (ti.getLockOwnerName() != null)
                    sb.append(" owned by \"").append(ti.getLockOwnerName()).append('"');
                sb.append('\n');
            }
            for (StackTraceElement e : ti.getStackTrace()) {
                sb.append("\tat ").append(e).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 데드락 감지 */
    public long[] findDeadlockedThreads() {
        long[] deadlocked = threadMX.findDeadlockedThreads();
        return deadlocked != null ? deadlocked : new long[0];
    }

    // -------------------------------------------------------------------------
    // OS 메모리
    // -------------------------------------------------------------------------

    /** 총 물리 메모리 (bytes), 미지원 시 -1 */
    public long totalPhysicalMemoryBytes() {
        return osMX != null ? osMX.getTotalMemorySize() : -1L;
    }

    /** 사용 가능한 물리 메모리 (bytes), 미지원 시 -1 */
    public long freePhysicalMemoryBytes() {
        return osMX != null ? osMX.getFreeMemorySize() : -1L;
    }

    /** 총 스왑 공간 (bytes), 미지원 시 -1 */
    public long totalSwapSpaceBytes() {
        return osMX != null ? osMX.getTotalSwapSpaceSize() : -1L;
    }

    /** 사용 가능한 스왑 공간 (bytes), 미지원 시 -1 */
    public long freeSwapSpaceBytes() {
        return osMX != null ? osMX.getFreeSwapSpaceSize() : -1L;
    }

    /** 프로세스에 커밋된 가상 메모리 (bytes), 미지원 시 -1 */
    public long committedVirtualMemoryBytes() {
        return osMX != null ? osMX.getCommittedVirtualMemorySize() : -1L;
    }

    /** 프로세스 CPU 사용 시간 (ns), 미지원 시 -1 */
    public long processCpuTimeNs() {
        return osMX != null ? osMX.getProcessCpuTime() : -1L;
    }

    // -------------------------------------------------------------------------
    // Buffer Pool (Direct / Mapped)
    // -------------------------------------------------------------------------

    private BufferPoolMXBean findBufPool(String name) {
        return bufPoolBeans.stream().filter(p -> name.equals(p.getName())).findFirst().orElse(null);
    }

    public long directBufferCount()        { BufferPoolMXBean p = findBufPool("direct");  return p != null ? p.getCount()         : 0L; }
    public long directBufferUsedBytes()    { BufferPoolMXBean p = findBufPool("direct");  return p != null ? p.getMemoryUsed()    : 0L; }
    public long directBufferCapacityBytes(){ BufferPoolMXBean p = findBufPool("direct");  return p != null ? p.getTotalCapacity() : 0L; }
    public long mappedBufferCount()        { BufferPoolMXBean p = findBufPool("mapped");  return p != null ? p.getCount()         : 0L; }
    public long mappedBufferUsedBytes()    { BufferPoolMXBean p = findBufPool("mapped");  return p != null ? p.getMemoryUsed()    : 0L; }

    // -------------------------------------------------------------------------
    // JIT 컴파일
    // -------------------------------------------------------------------------

    /** JIT 총 컴파일 시간 (ms), 미지원 시 -1 */
    public long jitCompilationTimeMs() {
        return (compileMX != null && compileMX.isCompilationTimeMonitoringSupported())
            ? compileMX.getTotalCompilationTime() : -1L;
    }

    // -------------------------------------------------------------------------
    // 메모리 풀 상세 (Eden / Survivor / Old Gen / Code Cache / Compressed Class)
    // -------------------------------------------------------------------------

    /** 메모리 풀별 {used, committed, max} (bytes) */
    public Map<String, long[]> memoryPoolDetails() {
        Map<String, long[]> result = new LinkedHashMap<>();
        for (MemoryPoolMXBean pool : memPoolBeans) {
            MemoryUsage u = pool.getUsage();
            result.put(pool.getName(), new long[]{u.getUsed(), u.getCommitted(), u.getMax()});
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // CPU
    // -------------------------------------------------------------------------

    /** 프로세스 CPU 사용률 (0.0 ~ 1.0), 미지원 시 -1 */
    public double processCpuLoad() {
        return osMX != null ? osMX.getProcessCpuLoad() : -1.0;
    }

    /** 시스템 CPU 사용률 (0.0 ~ 1.0), 미지원 시 -1 */
    public double systemCpuLoad() {
        return osMX != null ? osMX.getCpuLoad() : -1.0;
    }

    public int availableProcessors() {
        return Runtime.getRuntime().availableProcessors();
    }

    // -------------------------------------------------------------------------
    // 런타임
    // -------------------------------------------------------------------------

    public long uptimeMs()          { return runtimeMX.getUptime(); }
    public String jvmName()         { return runtimeMX.getVmName(); }
    public String jvmVersion()      { return runtimeMX.getVmVersion(); }
    public int loadedClassCount()   { return classloMX.getLoadedClassCount(); }
    public long totalLoadedClasses(){ return classloMX.getTotalLoadedClassCount(); }

    // -------------------------------------------------------------------------
    // 전체 스냅샷 (JSON 출력 등에 사용)
    // -------------------------------------------------------------------------

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();

        // Heap
        m.put("heap.used.mb",        toMb(heapUsedBytes()));
        m.put("heap.committed.mb",   toMb(heapCommittedBytes()));
        m.put("heap.max.mb",         toMb(heapMaxBytes()));
        m.put("heap.usage.percent",  String.format("%.1f%%", heapUsagePercent()));
        m.put("nonheap.used.mb",     toMb(nonHeapUsedBytes()));
        m.put("metaspace.used.mb",   toMb(metaspaceUsedBytes()));

        // Memory pools
        memoryPoolDetails().forEach((name, vals) -> {
            String key = "pool." + name.toLowerCase().replace(' ', '_') + ".used.mb";
            m.put(key, toMb(vals[0]));
        });

        // GC
        m.put("gc.total.count",      gcTotalCount());
        m.put("gc.total.time.ms",    gcTotalTimeMs());

        // Threads
        m.put("thread.count",         threadCount());
        m.put("thread.daemon",        daemonThreadCount());
        m.put("thread.peak",          peakThreadCount());
        m.put("thread.blocked",       blockedThreadCount());
        m.put("thread.waiting",       waitingThreadCount());
        m.put("thread.timed_waiting", timedWaitingThreadCount());
        m.put("thread.runnable",      runnableThreadCount());

        // CPU
        m.put("cpu.process",         String.format("%.1f%%", processCpuLoad() * 100));
        m.put("cpu.system",          String.format("%.1f%%", systemCpuLoad() * 100));
        m.put("cpu.processors",      availableProcessors());

        // OS Memory
        m.put("os.total.memory.mb",  toMb(totalPhysicalMemoryBytes()));
        m.put("os.free.memory.mb",   toMb(freePhysicalMemoryBytes()));
        m.put("os.total.swap.mb",    toMb(totalSwapSpaceBytes()));
        m.put("os.free.swap.mb",     toMb(freeSwapSpaceBytes()));

        // Buffer Pools
        m.put("buffer.direct.count", directBufferCount());
        m.put("buffer.direct.mb",    toMb(directBufferUsedBytes()));
        m.put("buffer.mapped.count", mappedBufferCount());
        m.put("buffer.mapped.mb",    toMb(mappedBufferUsedBytes()));

        // JIT
        m.put("jit.compile.time.ms", jitCompilationTimeMs());

        // Runtime
        m.put("uptime.ms",           uptimeMs());
        m.put("loaded.classes",      loadedClassCount());
        m.put("jvm.name",            jvmName());
        m.put("jvm.version",         jvmVersion());

        return m;
    }

    private static long toMb(long bytes) {
        return bytes / (1024 * 1024);
    }
}
