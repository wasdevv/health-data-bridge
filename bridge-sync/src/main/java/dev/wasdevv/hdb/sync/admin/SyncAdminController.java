package dev.wasdevv.hdb.sync.admin;

import dev.wasdevv.hdb.sync.erp.ErpException;
import dev.wasdevv.hdb.sync.reconciliation.Reconciliation;
import dev.wasdevv.hdb.sync.registry.RegistrySync;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Runs the scheduled jobs on demand, for demos and for after an incident. */
@RestController
@RequestMapping("/admin")
public class SyncAdminController {

    private final RegistrySync registry;
    private final Reconciliation reconciliation;

    public SyncAdminController(RegistrySync registry, Reconciliation reconciliation) {
        this.registry = registry;
        this.reconciliation = reconciliation;
    }

    @PostMapping("/registry/sync")
    public RegistrySync.Result syncRegistry() {
        return registry.run();
    }

    @PostMapping("/reconciliation/run")
    public Reconciliation.Report reconcile(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reconciliation.run(date);
    }

    @GetMapping("/reconciliation")
    public Reconciliation.Report latest(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reconciliation.latest(date).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "no reconciliation run for " + date));
    }

    /** The job needs the ERP: down is a 503 for the caller too, a 4xx from the ERP is a 502. */
    @ExceptionHandler(ErpException.class)
    ResponseEntity<Map<String, String>> erpFailed(ErpException e) {
        HttpStatus status = e instanceof ErpException.Transient ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
    }
}
