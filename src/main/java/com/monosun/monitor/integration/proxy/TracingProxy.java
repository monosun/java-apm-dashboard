package com.monosun.monitor.integration.proxy;

import com.monosun.monitor.annotation.Trace;
import com.monosun.monitor.core.Span;
import com.monosun.monitor.core.TraceContext;
import com.monosun.monitor.core.TransactionTracer;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * JDK 동적 프록시 기반 자동 추적.
 * Spring 없이 @Trace 어노테이션을 인터페이스에 적용할 수 있습니다.
 *
 * 지원 프레임워크: 모든 인터페이스 기반 자바 코드
 *   (Plain Java, Quarkus CDI, Micronaut, Helidon 등)
 *
 * 사용 예:
 *   UserService proxy = TracingProxy.wrap(UserService.class, new UserServiceImpl());
 *   proxy.getUser(1L); // → 자동으로 스팬 생성
 */
public class TracingProxy {

    @SuppressWarnings("unchecked")
    public static <T> T wrap(Class<T> interfaceType, T target) {
        return (T) Proxy.newProxyInstance(
            target.getClass().getClassLoader(),
            new Class<?>[]{interfaceType},
            new TracingHandler(target)
        );
    }

    private static class TracingHandler implements InvocationHandler {

        private final Object target;
        private final TransactionTracer tracer;

        TracingHandler(Object target) {
            this.target = target;
            this.tracer = TransactionTracer.getInstance();
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // @Trace 어노테이션이 없는 메서드는 추적하지 않음
            if (!shouldTrace(method)) {
                return method.invoke(target, args);
            }

            boolean newTrace = TraceContext.current() == null;
            if (newTrace) TraceContext.startTrace();

            Span span = tracer.startFromAnnotation(method, args);
            try {
                Object result = method.invoke(target, args);
                tracer.finish(span);
                return result;
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                tracer.finish(span, cause);
                throw cause;
            } finally {
                if (newTrace) TraceContext.clear();
            }
        }

        private boolean shouldTrace(Method method) {
            return method.isAnnotationPresent(Trace.class)
                || method.getDeclaringClass().isAnnotationPresent(Trace.class);
        }
    }
}
