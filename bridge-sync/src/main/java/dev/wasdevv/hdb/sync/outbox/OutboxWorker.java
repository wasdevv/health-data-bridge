package dev.wasdevv.hdb.sync.outbox;

import dev.wasdevv.hdb.core.erp.ErpInvoiceRequest;
import dev.wasdevv.hdb.sync.erp.ErpClient;
import dev.wasdevv.hdb.sync.erp.ErpException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers due outbox rows to the ERP. One {@link #runOnce()} is one database transaction:
 * claim a batch with {@code FOR UPDATE SKIP LOCKED}, prepare each row, send them to the ERP in
 * parallel (bounded by {@code concurrency}), record every outcome, commit. If the process dies before
 * the commit, the rows return to PENDING and are sent again with the same Idempotency-Key, which the
 * ERP answers with the invoice it already issued.
 *
 * <p>While the circuit breaker is open the worker does not claim anything: the outbox grows, nothing
 * is lost, and no attempt is spent on an ERP that is known to be down.
 */
public class OutboxWorker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private static final int MAX_ERROR_LENGTH = 1000;

    public record RunResult(int claimed, int delivered, int retried, int dead, int paused) {
        static final RunResult NOTHING = new RunResult(0, 0, 0, 0, 0);
    }

    private sealed interface Outcome {
        record Delivered(String invoiceId, boolean replayed) implements Outcome {
        }

        record Failed(boolean permanent, String reason) implements Outcome {
        }

        record Paused() implements Outcome {
        }
    }

    private record Work(OutboxEntry entry, ErpInvoiceRequest request, Outcome early) {
    }

    private final OutboxRepository outbox;
    private final InvoicePreparer preparer;
    private final ErpClient erp;
    private final CircuitBreaker breaker;
    private final RetryPolicy retry;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int batchSize;
    private final ExecutorService senders;
    private final Counter delivered;
    private final Counter retried;
    private final Counter dead;

    public OutboxWorker(OutboxRepository outbox, InvoicePreparer preparer, ErpClient erp, CircuitBreaker breaker,
                        RetryPolicy retry, TransactionTemplate tx, Clock clock, int batchSize, int concurrency,
                        MeterRegistry meters) {
        this.outbox = outbox;
        this.preparer = preparer;
        this.erp = erp;
        this.breaker = breaker;
        this.retry = retry;
        this.tx = tx;
        this.clock = clock;
        this.batchSize = batchSize;
        this.senders = Executors.newFixedThreadPool(concurrency, Thread.ofPlatform().name("outbox-sender-", 0).daemon().factory());
        this.delivered = Counter.builder("bridge.outbox.delivered").description("Invoices confirmed by the ERP").register(meters);
        this.retried = Counter.builder("bridge.outbox.retried").description("Failed attempts scheduled for retry").register(meters);
        this.dead = Counter.builder("bridge.outbox.dead.total").description("Rows moved to the dead letter queue").register(meters);
    }

    /** Runs batches until one comes back short or makes no progress. Returns the totals. */
    public RunResult drain() {
        RunResult total = RunResult.NOTHING;
        while (true) {
            RunResult r = runOnce();
            total = new RunResult(total.claimed() + r.claimed(), total.delivered() + r.delivered(),
                    total.retried() + r.retried(), total.dead() + r.dead(), total.paused() + r.paused());
            if (r.claimed() < batchSize || r.paused() > 0 || r.delivered() + r.retried() + r.dead() == 0) {
                return total;
            }
        }
    }

    public RunResult runOnce() {
        if (!breakerPermitsWork()) {
            return RunResult.NOTHING;
        }
        RunResult result = tx.execute(status -> processBatch());
        return result == null ? RunResult.NOTHING : result;
    }

    private boolean breakerPermitsWork() {
        if (breaker.getState() != CircuitBreaker.State.OPEN) {
            return true;
        }
        // OPEN moves to HALF_OPEN on the first permission request after the wait; give the permit back
        if (breaker.tryAcquirePermission()) {
            breaker.releasePermission();
            return true;
        }
        return false;
    }

    private RunResult processBatch() {
        Instant now = clock.instant();
        List<OutboxEntry> batch = outbox.claimDue(batchSize, now);
        if (batch.isEmpty()) {
            return RunResult.NOTHING;
        }

        List<Work> work = new ArrayList<>();
        for (OutboxEntry entry : batch) {
            try {
                work.add(new Work(entry, preparer.prepare(entry.aggregateId()), null));
            } catch (InvoicePreparer.PreparationException e) {
                work.add(new Work(entry, null, new Outcome.Failed(e.permanent(), e.getMessage())));
            } catch (RuntimeException e) {
                // one unreadable row must not block the batch; it is retried and, if it keeps failing, dies
                work.add(new Work(entry, null, new Outcome.Failed(false, "preparation failed: " + e.getClass().getSimpleName())));
            }
        }

        List<Future<Outcome>> sent = new ArrayList<>();
        for (Work w : work) {
            sent.add(w.early() != null ? null : senders.submit(() -> send(w.entry(), w.request())));
        }

        int ok = 0, retrying = 0, deadNow = 0, paused = 0;
        Instant done = clock.instant();
        for (int i = 0; i < work.size(); i++) {
            Work w = work.get(i);
            Outcome outcome = w.early() != null ? w.early() : await(sent.get(i));
            OutboxEntry e = w.entry();
            int attempts = e.attempts() + 1;
            switch (outcome) {
                case Outcome.Delivered d -> {
                    outbox.markDone(e.id(), attempts, d.invoiceId(), done);
                    delivered.increment();
                    ok++;
                    log.info("outbox {} Encounter/{} delivered as {}{}", e.id(), e.aggregateId(), d.invoiceId(),
                            d.replayed() ? " (replayed by the ERP)" : "");
                }
                case Outcome.Paused p -> paused++;
                case Outcome.Failed f when f.permanent() || retry.exhausted(attempts) -> {
                    String reason = (f.permanent() ? "permanent: " : "gave up after " + attempts + " attempts: ") + f.reason();
                    outbox.markDead(e.id(), attempts, truncate(reason), done);
                    dead.increment();
                    deadNow++;
                    log.warn("outbox {} Encounter/{} moved to the dead letter queue: {}", e.id(), e.aggregateId(), truncate(reason));
                }
                case Outcome.Failed f -> {
                    Instant next = done.plus(retry.delayAfter(attempts));
                    outbox.reschedule(e.id(), attempts, next, truncate(f.reason()), done);
                    retried.increment();
                    retrying++;
                    log.info("outbox {} Encounter/{} attempt {} failed, retry at {}: {}", e.id(), e.aggregateId(),
                            attempts, next, truncate(f.reason()));
                }
            }
        }
        return new RunResult(batch.size(), ok, retrying, deadNow, paused);
    }

    private Outcome send(OutboxEntry entry, ErpInvoiceRequest request) {
        try {
            ErpClient.InvoiceReceipt receipt = breaker.executeCallable(() -> erp.createInvoice(entry.id().toString(), request));
            return new Outcome.Delivered(receipt.invoiceId(), receipt.replayed());
        } catch (CallNotPermittedException e) {
            return new Outcome.Paused();
        } catch (ErpException.Permanent e) {
            return new Outcome.Failed(true, e.getMessage());
        } catch (ErpException.Transient e) {
            return new Outcome.Failed(false, e.getMessage());
        } catch (Exception e) {
            return new Outcome.Failed(false, e.getClass().getSimpleName());
        }
    }

    private static Outcome await(Future<Outcome> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome.Failed(false, "interrupted");
        } catch (ExecutionException e) {
            return new Outcome.Failed(false, e.getCause().getClass().getSimpleName());
        }
    }

    private static String truncate(String s) {
        return s.length() <= MAX_ERROR_LENGTH ? s : s.substring(0, MAX_ERROR_LENGTH) + "...";
    }

    @Override
    public void destroy() {
        senders.shutdownNow();
    }
}
