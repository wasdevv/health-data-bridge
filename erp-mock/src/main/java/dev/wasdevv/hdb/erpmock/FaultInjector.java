package dev.wasdevv.hdb.erpmock;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Per-endpoint failure modes. {@link #beforeWrite} and {@link #beforeRead} run before the endpoint does
 * anything and can delay, answer 503 (transient) or, for writes, 422 (permanent). {@link #afterCommit} runs after an invoice is stored and
 * can still answer 503: the caller sees a failure for something that happened, which is exactly the
 * case the Idempotency-Key exists for.
 */
@Component
public class FaultInjector {

    public static final String INVOICES = "invoices";
    public static final String CUSTOMERS = "customers";
    public static final String LOCATIONS = "locations";

    private final Map<String, Model.Faults> faults = new ConcurrentHashMap<>();
    private final Random random;
    private final AtomicLong injected = new AtomicLong();

    public FaultInjector(@Value("${erp.faults.random-seed:42}") long seed) {
        this.random = new Random(seed);
    }

    public void set(String endpoint, Model.Faults config) {
        if (!Map.of(INVOICES, 1, CUSTOMERS, 1, LOCATIONS, 1).containsKey(endpoint)) {
            throw new ErpError(HttpStatus.NOT_FOUND, "unknown_endpoint", "faults exist for invoices, customers, locations");
        }
        faults.put(endpoint, config.withDefaults());
    }

    public Map<String, Model.Faults> all() {
        return Map.of(INVOICES, get(INVOICES), CUSTOMERS, get(CUSTOMERS), LOCATIONS, get(LOCATIONS));
    }

    public void reset() {
        faults.clear();
    }

    public long injected() {
        return injected.get();
    }

    void beforeRead(String endpoint) {
        before(endpoint, false);
    }

    void beforeWrite(String endpoint) {
        before(endpoint, true);
    }

    /** A permanent rejection only makes sense for a write: a read has no data to reject. */
    private void before(String endpoint, boolean write) {
        Model.Faults f = get(endpoint);
        if (f.latencyMs() > 0) {
            try {
                Thread.sleep(f.latencyMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (f.down()) {
            injected.incrementAndGet();
            throw new ErpError(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", "ERP is down (injected)");
        }
        if (roll(f.errorRate())) {
            injected.incrementAndGet();
            throw new ErpError(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", "intermittent failure (injected)");
        }
        if (write && roll(f.permanentErrorRate())) {
            injected.incrementAndGet();
            throw new ErpError(HttpStatus.UNPROCESSABLE_ENTITY, "rejected", "invalid data (injected)");
        }
    }

    void afterCommit(String endpoint) {
        if (roll(get(endpoint).failAfterCommitRate())) {
            injected.incrementAndGet();
            throw new ErpError(HttpStatus.SERVICE_UNAVAILABLE, "unavailable",
                    "response lost after the invoice was stored (injected)");
        }
    }

    private Model.Faults get(String endpoint) {
        return faults.getOrDefault(endpoint, Model.Faults.NONE);
    }

    private boolean roll(double rate) {
        if (rate <= 0) {
            return false;
        }
        synchronized (random) {
            return random.nextDouble() < rate;
        }
    }
}
