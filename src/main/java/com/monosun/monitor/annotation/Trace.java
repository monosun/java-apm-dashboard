package com.monosun.monitor.annotation;

import java.lang.annotation.*;

/**
 * 메서드 또는 클래스에 붙여 성능 추적 대상으로 지정합니다.
 * 클래스에 붙이면 모든 public 메서드에 적용됩니다.
 *
 * 사용 예:
 *   @Trace(name = "user.fetch", tags = {"layer=service"})
 *   public User getUser(Long id) { ... }
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Trace {
    String name() default "";           // 스팬 이름 (기본: ClassName.methodName)
    String[] tags() default {};         // 정적 태그 ("key=value" 형식)
    boolean logParams() default false;  // 파라미터 값 기록 여부
    boolean recordError() default true; // 예외 발생 시 기록 여부
}
