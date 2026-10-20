package dev.wasdevv.hdb.core.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** An invoice that passed every billing rule, in the bridge's own terms (not the ERP's wire format). */
public record InvoiceDraft(
        String encounterId,
        String patientId,
        String customerCode,
        LocalDate issueDate,
        String currency,
        List<String> diagnosisCodes,
        List<Line> lines,
        BigDecimal total) {

    public InvoiceDraft {
        diagnosisCodes = List.copyOf(diagnosisCodes);
        lines = List.copyOf(lines);
    }

    public record Line(String code, String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
    }
}
