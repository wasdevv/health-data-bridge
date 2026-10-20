package dev.wasdevv.hdb.sync.erp;

import dev.wasdevv.hdb.sync.SyncProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;

/**
 * The breaker in front of the ERP. Only {@link ErpException.Transient} counts as a failure: a 422 for
 * bad data means the ERP is working, and must not pause delivery of everyone else's invoices.
 * The window is count-based and the breaker needs a full window before it can open, so a few early
 * errors do not stop the worker.
 */
public final class ErpCircuitBreaker {

    public static final String NAME = "erp";

    private ErpCircuitBreaker() {
    }

    public static CircuitBreakerConfig config(SyncProperties p, Clock clock) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(p.breakerWindow())
                .minimumNumberOfCalls(p.breakerWindow())
                .failureRateThreshold(p.breakerFailureRate())
                .waitDurationInOpenState(p.breakerOpenWait())
                .permittedNumberOfCallsInHalfOpenState(p.breakerHalfOpenCalls())
                .recordException(e -> e instanceof ErpException.Transient)
                .ignoreExceptions(ErpException.Permanent.class)
                .clock(clock)
                .build();
    }

    public static CircuitBreaker create(CircuitBreakerRegistry registry, SyncProperties p, Clock clock) {
        return registry.circuitBreaker(NAME, config(p, clock));
    }
}
