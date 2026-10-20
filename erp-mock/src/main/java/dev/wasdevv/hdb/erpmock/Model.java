package dev.wasdevv.hdb.erpmock;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Wire types of the ERP API, as documented in {@code static/openapi.yaml}. */
public final class Model {

    private Model() {
    }

    public record Customer(String code, String name, String taxId, boolean active, Instant updatedAt) {
    }

    public record Location(String code, String name, String city, boolean active, Instant updatedAt) {
    }

    public record InvoiceRequest(
            @NotBlank String encounterId,
            @NotBlank String customerCode,
            @NotBlank String patientReference,
            @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String issueDate,
            @NotBlank String currency,
            @NotNull List<String> diagnosisCodes,
            @NotEmpty List<@Valid Line> lines,
            @NotNull BigDecimal total) {

        public record Line(@NotBlank String code, String description, @NotNull BigDecimal quantity,
                           @NotNull BigDecimal unitPrice, @NotNull BigDecimal amount) {
        }
    }

    public record Invoice(String invoiceId, String encounterId, String customerCode, String issueDate, String currency,
                          BigDecimal total, String status, Instant createdAt) {
    }

    public record Faults(Integer latencyMs, Double errorRate, Double permanentErrorRate, Double failAfterCommitRate,
                         Boolean down) {

        public static final Faults NONE = new Faults(0, 0.0, 0.0, 0.0, false);

        Faults withDefaults() {
            return new Faults(latencyMs == null ? 0 : latencyMs, errorRate == null ? 0 : errorRate,
                    permanentErrorRate == null ? 0 : permanentErrorRate,
                    failAfterCommitRate == null ? 0 : failAfterCommitRate, down != null && down);
        }
    }

    public record Stats(int invoices, int distinctEncounters, List<String> duplicatedEncounters, long requests,
                        long replays, long faultsInjected) {
    }
}
