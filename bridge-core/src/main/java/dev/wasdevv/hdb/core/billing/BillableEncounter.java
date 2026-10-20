package dev.wasdevv.hdb.core.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What billing needs from the FHIR side, already pulled out of Encounter, Coverage, Claim and Condition.
 * It carries the patient's resource id only: name and CPF never leave the FHIR store.
 */
public record BillableEncounter(
        String encounterId,
        String encounterStatus,
        String patientId,
        LocalDate serviceDate,
        boolean coverageActive,
        String customerCode,
        List<String> diagnosisCodes,
        List<ClaimItem> items,
        BigDecimal claimTotal,
        String currency) {

    public BillableEncounter {
        diagnosisCodes = List.copyOf(diagnosisCodes);
        items = List.copyOf(items);
    }

    public record ClaimItem(String code, String description, BigDecimal quantity, BigDecimal unitPrice, String currency) {
    }
}
