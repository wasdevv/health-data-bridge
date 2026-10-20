package dev.wasdevv.hdb.sync.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.wasdevv.hdb.sync.SyncProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** The breaker's decisions with time under the test's control: no sleeping for 30 seconds. */
class ErpCircuitBreakerTest {

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-20T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final MutableClock clock = new MutableClock();
    final SyncProperties props = new SyncProperties(null, null, null, false, null, 0, 0, 0, null, null, 0, 0, null, 0);
    final CircuitBreaker breaker = ErpCircuitBreaker.create(CircuitBreakerRegistry.ofDefaults(), props, clock);

    void succeed() {
        breaker.executeSupplier(() -> "ok");
    }

    void failTransient() {
        assertThatThrownBy(() -> breaker.executeSupplier(() -> {
            throw new ErpException.Transient("503", null);
        })).isInstanceOf(ErpException.Transient.class);
    }

    void failPermanent() {
        assertThatThrownBy(() -> breaker.executeSupplier(() -> {
            throw new ErpException.Permanent(422, "422");
        })).isInstanceOf(ErpException.Permanent.class);
    }

    @Test
    void defaultsAreThePrds() {
        var c = breaker.getCircuitBreakerConfig();
        assertThat(c.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(c.getSlidingWindowSize()).isEqualTo(20);
        assertThat(c.getMinimumNumberOfCalls()).isEqualTo(20);
        assertThat(c.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(5);
    }

    @Test
    void staysClosedUntilAFullWindowEvenIfEverythingFails() {
        for (int i = 0; i < 19; i++) {
            failTransient();
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        failTransient();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void opensAtFiftyPercentOfTwentyCalls() {
        for (int i = 0; i < 11; i++) {
            succeed();
        }
        for (int i = 0; i < 9; i++) {
            failTransient();
        }
        assertThat(breaker.getState()).as("45%").isEqualTo(CircuitBreaker.State.CLOSED);

        failTransient(); // window now holds 10 successes and 10 failures
        assertThat(breaker.getState()).as("50%").isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void permanentRejectionsDoNotCountAsErpFailures() {
        for (int i = 0; i < 40; i++) {
            failPermanent();
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void halfOpensAfterThirtySecondsAndClosesOnHealthyCalls() {
        for (int i = 0; i < 20; i++) {
            failTransient();
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        clock.advance(Duration.ofSeconds(29));
        assertThatThrownBy(this::succeed).isInstanceOf(CallNotPermittedException.class);

        clock.advance(Duration.ofSeconds(1).plusMillis(1));
        succeed();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        for (int i = 0; i < 4; i++) {
            succeed();
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void reopensWhenHalfOpenCallsKeepFailing() {
        for (int i = 0; i < 20; i++) {
            failTransient();
        }
        clock.advance(Duration.ofSeconds(30).plusMillis(1));
        for (int i = 0; i < 5; i++) {
            failTransient();
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        clock.advance(Duration.ofSeconds(10));
        assertThatThrownBy(this::succeed).isInstanceOf(CallNotPermittedException.class);
    }
}
