package dev.wasdevv.hdb.erpmock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The ERP's books, in memory. Restarting the mock empties them, so "ERP down" in demos and tests is
 * the {@code down} fault, not a stopped container.
 */
@Component
public class ErpStore {

    private record Replay(Model.InvoiceRequest request, Model.Invoice invoice) {
    }

    private final Clock clock;
    private final Map<String, Model.Customer> customers = new LinkedHashMap<>();
    private final Map<String, Model.Location> locations = new LinkedHashMap<>();
    private final Map<String, Model.Invoice> invoices = new LinkedHashMap<>();
    private final Map<String, Replay> idempotency = new LinkedHashMap<>();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong replays = new AtomicLong();

    public ErpStore(Clock clock) {
        this.clock = clock;
    }

    public record Result(Model.Invoice invoice, boolean replayed) {
    }

    public synchronized Result createInvoice(String idempotencyKey, Model.InvoiceRequest request) {
        requests.incrementAndGet();
        Replay previous = idempotency.get(idempotencyKey);
        if (previous != null) {
            // same key with a different body is a client bug, not a retry
            if (!previous.request().equals(request)) {
                throw new ErpError(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency_key_reused",
                        "Idempotency-Key was already used with a different request body");
            }
            replays.incrementAndGet();
            return new Result(previous.invoice(), true);
        }
        validate(request);
        Model.Invoice invoice = new Model.Invoice("INV-" + UUID.randomUUID(), request.encounterId(),
                request.customerCode(), request.issueDate(), request.currency(),
                request.total().setScale(2, RoundingMode.HALF_EVEN), "ISSUED", clock.instant());
        invoices.put(invoice.invoiceId(), invoice);
        idempotency.put(idempotencyKey, new Replay(request, invoice));
        return new Result(invoice, false);
    }

    /** Planted data for the reconciliation demo: goes around validation and idempotency on purpose. */
    synchronized void plantInvoice(Model.Invoice invoice) {
        invoices.put(invoice.invoiceId(), invoice);
    }

    public synchronized List<Model.Invoice> invoices(String issueDate, String encounterId) {
        return invoices.values().stream()
                .filter(i -> issueDate == null || i.issueDate().equals(issueDate))
                .filter(i -> encounterId == null || i.encounterId().equals(encounterId))
                .toList();
    }

    public synchronized void upsertCustomer(Model.Customer customer) {
        customers.put(customer.code(), new Model.Customer(customer.code(), customer.name(), customer.taxId(),
                customer.active(), clock.instant()));
    }

    public synchronized void upsertLocation(Model.Location location) {
        locations.put(location.code(), new Model.Location(location.code(), location.name(), location.city(),
                location.active(), clock.instant()));
    }

    /** {@code updatedSince} is inclusive: a record updated in the same instant as the cursor is sent again, never skipped. */
    public synchronized List<Model.Customer> customers(Instant updatedSince) {
        return customers.values().stream()
                .filter(c -> updatedSince == null || !c.updatedAt().isBefore(updatedSince))
                .sorted(Comparator.comparing(Model.Customer::updatedAt).thenComparing(Model.Customer::code))
                .toList();
    }

    public synchronized List<Model.Location> locations(Instant updatedSince) {
        return locations.values().stream()
                .filter(l -> updatedSince == null || !l.updatedAt().isBefore(updatedSince))
                .sorted(Comparator.comparing(Model.Location::updatedAt).thenComparing(Model.Location::code))
                .toList();
    }

    public synchronized Model.Stats stats(long faultsInjected) {
        Map<String, Integer> perEncounter = new LinkedHashMap<>();
        invoices.values().forEach(i -> perEncounter.merge(i.encounterId(), 1, Integer::sum));
        List<String> duplicated = new ArrayList<>();
        perEncounter.forEach((enc, n) -> {
            if (n > 1) {
                duplicated.add(enc);
            }
        });
        return new Model.Stats(invoices.size(), perEncounter.size(), duplicated, requests.get(), replays.get(),
                faultsInjected);
    }

    public synchronized void clear() {
        customers.clear();
        locations.clear();
        invoices.clear();
        idempotency.clear();
        requests.set(0);
        replays.set(0);
    }

    private void validate(Model.InvoiceRequest r) {
        Model.Customer customer = customers.get(r.customerCode());
        if (customer == null) {
            throw new ErpError(HttpStatus.UNPROCESSABLE_ENTITY, "unknown_customer", "customer " + r.customerCode() + " does not exist");
        }
        if (!customer.active()) {
            throw new ErpError(HttpStatus.UNPROCESSABLE_ENTITY, "inactive_customer", "customer " + r.customerCode() + " is inactive");
        }
        if (!"BRL".equals(r.currency())) {
            throw new ErpError(HttpStatus.UNPROCESSABLE_ENTITY, "unsupported_currency", "only BRL is accepted");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Model.InvoiceRequest.Line line : r.lines()) {
            BigDecimal expected = line.quantity().multiply(line.unitPrice()).setScale(2, RoundingMode.HALF_EVEN);
            if (expected.compareTo(line.amount()) != 0) {
                throw new ErpError(HttpStatus.UNPROCESSABLE_ENTITY, "line_amount_mismatch",
                        "line " + line.code() + ": quantity x unitPrice is " + expected + ", not " + line.amount());
            }
            sum = sum.add(line.amount());
        }
        if (sum.compareTo(r.total()) != 0) {
            throw new ErpError(HttpStatus.UNPROCESSABLE_ENTITY, "total_mismatch",
                    "lines add up to " + sum + ", not " + r.total());
        }
    }
}
