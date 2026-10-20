package dev.wasdevv.hdb.erpmock;

import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ErpController {

    private final ErpStore store;
    private final FaultInjector faults;

    public ErpController(ErpStore store, FaultInjector faults) {
        this.store = store;
        this.faults = faults;
    }

    /** 201 for a new invoice; a repeated Idempotency-Key with the same body gets the same invoice back, as 200. */
    @PostMapping("/invoices")
    public ResponseEntity<Model.Invoice> createInvoice(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                       @Valid @RequestBody Model.InvoiceRequest request) {
        faults.beforeWrite(FaultInjector.INVOICES);
        if (idempotencyKey.isBlank()) {
            throw new ErpError(HttpStatus.BAD_REQUEST, "missing_idempotency_key", "Idempotency-Key is required");
        }
        ErpStore.Result result = store.createInvoice(idempotencyKey, request);
        faults.afterCommit(FaultInjector.INVOICES);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(result.invoice());
    }

    @GetMapping("/invoices")
    public List<Model.Invoice> invoices(@RequestParam(required = false) String issueDate,
                                        @RequestParam(required = false) String encounterId) {
        faults.beforeRead(FaultInjector.INVOICES);
        return store.invoices(issueDate, encounterId);
    }

    @GetMapping("/customers")
    public List<Model.Customer> customers(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedSince) {
        faults.beforeRead(FaultInjector.CUSTOMERS);
        return store.customers(updatedSince);
    }

    @GetMapping("/locations")
    public List<Model.Location> locations(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedSince) {
        faults.beforeRead(FaultInjector.LOCATIONS);
        return store.locations(updatedSince);
    }
}
