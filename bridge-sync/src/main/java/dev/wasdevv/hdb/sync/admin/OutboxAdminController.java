package dev.wasdevv.hdb.sync.admin;

import dev.wasdevv.hdb.sync.outbox.OutboxEntry;
import dev.wasdevv.hdb.sync.outbox.OutboxRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The dead letter queue: list it, and put a row back in line after fixing what killed it. A revived
 * row keeps its id, so the ERP still sees the same Idempotency-Key.
 */
@RestController
@RequestMapping("/admin/outbox")
public class OutboxAdminController {

    private final OutboxRepository outbox;
    private final Clock clock;

    public OutboxAdminController(OutboxRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @GetMapping
    public List<OutboxEntry> list(@RequestParam(defaultValue = "DEAD") OutboxEntry.Status status,
                                  @RequestParam(defaultValue = "100") int limit) {
        return outbox.list(status, Math.min(Math.max(limit, 1), 1000));
    }

    @GetMapping("/{id}")
    public OutboxEntry get(@PathVariable UUID id) {
        return outbox.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no outbox row " + id));
    }

    @PostMapping("/{id}/retry")
    @Transactional
    public OutboxEntry retry(@PathVariable UUID id) {
        OutboxEntry entry = get(id);
        if (!outbox.revive(id, clock.instant())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "outbox row " + id + " is " + entry.status() + "; only DEAD rows can be retried");
        }
        return get(id);
    }
}
