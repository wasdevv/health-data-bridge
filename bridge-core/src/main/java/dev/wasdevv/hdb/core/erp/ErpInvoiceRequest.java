package dev.wasdevv.hdb.core.erp;

import java.math.BigDecimal;
import java.util.List;

/** Body of {@code POST /api/invoices}, field for field as in the ERP's OpenAPI contract. */
public record ErpInvoiceRequest(
        String encounterId,
        String customerCode,
        String patientReference,
        String issueDate,
        String currency,
        List<String> diagnosisCodes,
        List<Line> lines,
        BigDecimal total) {

    public record Line(String code, String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
    }
}
