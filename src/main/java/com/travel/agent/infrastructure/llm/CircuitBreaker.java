package com.travel.agent.infrastructure.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 三态熔断：CLOSED →（连续失败）→ OPEN →（冷却结束）→ HALF_OPEN →（探测成功）→ CLOSED。
 */
@Slf4j
@Component
public class CircuitBreaker {

    private final Clock clock;
    private final ConcurrentHashMap<String, BreakerState> breakers = new ConcurrentHashMap<>();

    @Value("${travel.agent.circuit.failure-threshold:5}")
    private int failureThreshold;

    @Value("${travel.agent.circuit.open-seconds:30}")
    private long openSeconds;

    @Value("${travel.agent.circuit.half-open-max-tries:3}")
    private int halfOpenMaxTries;

    public CircuitBreaker() {
        this(Clock.systemUTC());
    }

    public CircuitBreaker(Clock clock) {
        this.clock = clock;
    }

    public <T> T execute(String name, Supplier<T> primary, Supplier<T> fallback) {
        BreakerState st = breakers.computeIfAbsent(name, n -> new BreakerState());
        State current = st.state.get();
        Instant now = clock.instant();

        if (current == State.OPEN) {
            if (now.isBefore(st.openUntil)) {
                log.debug("breaker {} OPEN, use fallback", name);
                return fallback.get();
            }
            st.state.compareAndSet(State.OPEN, State.HALF_OPEN);
            st.halfOpenAttempts.set(0);
        }

        if (st.state.get() == State.HALF_OPEN) {
            int attempt = st.halfOpenAttempts.incrementAndGet();
            if (attempt > halfOpenMaxTries) {
                tripOpen(st, now);
                return fallback.get();
            }
        }

        try {
            T value = primary.get();
            onSuccess(st);
            return value;
        } catch (RuntimeException e) {
            onFailure(st, now);
            log.warn("breaker {} failure: {}", name, e.getMessage());
            return fallback.get();
        }
    }

    public boolean allow(String name) {
        BreakerState st = breakers.computeIfAbsent(name, n -> new BreakerState());
        State current = st.state.get();
        Instant now = clock.instant();
        if (current == State.OPEN && now.isBefore(st.openUntil)) {
            return false;
        }
        return true;
    }

    public void recordSuccess(String name) {
        BreakerState st = breakers.get(name);
        if (st != null) {
            onSuccess(st);
        }
    }

    public void recordFailure(String name) {
        BreakerState st = breakers.get(name);
        if (st != null) {
            onFailure(st, clock.instant());
        }
    }

    private void onSuccess(BreakerState st) {
        st.failures.set(0);
        st.state.set(State.CLOSED);
        st.halfOpenAttempts.set(0);
    }

    private void onFailure(BreakerState st, Instant now) {
        int n = st.failures.incrementAndGet();
        if (st.state.get() == State.HALF_OPEN || n >= failureThreshold) {
            tripOpen(st, now);
        }
    }

    private void tripOpen(BreakerState st, Instant now) {
        st.state.set(State.OPEN);
        st.openUntil = now.plusSeconds(openSeconds);
        st.failures.set(0);
        log.info("breaker tripped OPEN until {}", st.openUntil);
    }

    enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private static final class BreakerState {
        private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicInteger halfOpenAttempts = new AtomicInteger();
        private volatile Instant openUntil = Instant.EPOCH;
    }
}
