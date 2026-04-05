package com.travel.agent.infrastructure.trace;

import java.util.UUID;

/**
 * 请求级 traceId 持有者（可与 MDC 集成）。
 */
public final class TraceContext {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private TraceContext() {
    }

    public static String getTraceId() {
        return TRACE_ID.get();
    }

    public static String ensureTraceId() {
        String id = TRACE_ID.get();
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "");
            TRACE_ID.set(id);
        }
        return id;
    }

    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
    }

    public static void clear() {
        TRACE_ID.remove();
    }
}
