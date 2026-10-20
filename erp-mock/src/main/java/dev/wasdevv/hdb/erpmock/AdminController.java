package dev.wasdevv.hdb.erpmock;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Test and demo controls: fault modes, registry edits, counters. Not part of the ERP contract. */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final ErpStore store;
    private final FaultInjector faults;
    private final Seed seed;

    public AdminController(ErpStore store, FaultInjector faults, Seed seed) {
        this.store = store;
        this.faults = faults;
        this.seed = seed;
    }

    @GetMapping("/faults")
    public Map<String, Model.Faults> faults() {
        return faults.all();
    }

    @PutMapping("/faults/{endpoint}")
    public Map<String, Model.Faults> setFaults(@PathVariable String endpoint, @RequestBody Model.Faults config) {
        faults.set(endpoint, config);
        return faults.all();
    }

    @DeleteMapping("/faults")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearFaults() {
        faults.reset();
    }

    @PutMapping("/customers/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void upsertCustomer(@PathVariable String code, @RequestBody Model.Customer customer) {
        store.upsertCustomer(new Model.Customer(code, customer.name(), customer.taxId(), customer.active(), null));
    }

    @PutMapping("/locations/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void upsertLocation(@PathVariable String code, @RequestBody Model.Location location) {
        store.upsertLocation(new Model.Location(code, location.name(), location.city(), location.active(), null));
    }

    @GetMapping("/stats")
    public Model.Stats stats() {
        return store.stats(faults.injected());
    }

    /** Empties the books and faults, then seeds again ({@code plantedDivergence=false} for a clean slate). */
    @PostMapping("/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@RequestParam(defaultValue = "false") boolean plantedDivergence) {
        faults.reset();
        store.clear();
        seed.load(plantedDivergence);
    }
}
