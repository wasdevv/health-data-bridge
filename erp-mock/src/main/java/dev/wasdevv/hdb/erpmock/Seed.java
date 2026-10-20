package dev.wasdevv.hdb.erpmock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Fictitious registry: four insurers (the payors of the demo coverages), one inactive insurer and
 * three units. With the planted divergence on, one invoice points to encounter {@code enc-planted},
 * which does not exist on the FHIR side: reconciliation has to find it.
 */
@Component
public class Seed {

    public static final String PLANTED_ENCOUNTER = "enc-planted";
    public static final String PLANTED_DATE = "2026-10-03";

    private final ErpStore store;
    private final boolean enabled;
    private final boolean plantedDivergence;

    public Seed(ErpStore store, @Value("${erp.seed.enabled:true}") boolean enabled,
                @Value("${erp.seed.planted-divergence:true}") boolean plantedDivergence) {
        this.store = store;
        this.enabled = enabled;
        this.plantedDivergence = plantedDivergence;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        if (enabled) {
            load(plantedDivergence);
        }
    }

    void load(boolean withPlantedDivergence) {
        store.upsertCustomer(new Model.Customer("CUST-001", "Operadora Horizonte Saúde (fictícia)", "11222333000181", true, null));
        store.upsertCustomer(new Model.Customer("CUST-002", "Cooperativa Vale Verde (fictícia)", "22333444000105", true, null));
        store.upsertCustomer(new Model.Customer("CUST-003", "Plano Litoral (fictício)", "33444555000120", true, null));
        store.upsertCustomer(new Model.Customer("CUST-004", "Autogestão Serra Azul (fictícia)", "44555666000136", true, null));
        store.upsertCustomer(new Model.Customer("CUST-099", "Operadora Encerrada (fictícia)", "55666777000193", false, null));
        store.upsertLocation(new Model.Location("LOC-01", "Unidade Centro", "Curitiba", true, null));
        store.upsertLocation(new Model.Location("LOC-02", "Unidade Norte", "Londrina", true, null));
        store.upsertLocation(new Model.Location("LOC-03", "Pronto Atendimento Sul", "Maringá", true, null));
        if (withPlantedDivergence) {
            store.plantInvoice(new Model.Invoice("INV-PLANTED", PLANTED_ENCOUNTER, "CUST-001", PLANTED_DATE, "BRL",
                    new BigDecimal("150.00"), "ISSUED", Instant.parse("2026-10-03T12:00:00Z")));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
