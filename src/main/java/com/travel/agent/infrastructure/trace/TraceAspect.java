package com.travel.agent.infrastructure.trace;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 基于 AOP 的入口追踪：为每次 Web 请求生成 traceId，写入 MDC，便于日志与排错。
 * 仅拦截 Controller，避免与 Orchestrator 嵌套时提前 {@link TraceContext#clear()}。
 */
@Slf4j
@Aspect
@Component
@Order(0)
public class TraceAspect {

    private static final String TRACE_KEY = "traceId";

    @Around("execution(* com.travel.agent.controller..*(..))")
    public Object traceController(ProceedingJoinPoint pjp) throws Throwable {
        TraceContext.ensureTraceId();
        String traceId = TraceContext.getTraceId();
        MDC.put(TRACE_KEY, traceId != null ? traceId : "");
        long t0 = System.nanoTime();
        try {
            return pjp.proceed();
        } catch (Throwable ex) {
            log.warn("{}#{} failed trace={} msg={}",
                    pjp.getTarget().getClass().getSimpleName(),
                    pjp.getSignature().getName(),
                    traceId,
                    ex.getMessage());
            throw ex;
        } finally {
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            if (log.isDebugEnabled()) {
                log.debug("{}#{} {} ms trace={}",
                        pjp.getTarget().getClass().getSimpleName(),
                        pjp.getSignature().getName(),
                        ms,
                        traceId);
            }
            MDC.remove(TRACE_KEY);
            TraceContext.clear();
        }
    }
}
