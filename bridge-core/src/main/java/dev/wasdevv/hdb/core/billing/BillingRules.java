package dev.wasdevv.hdb.core.billing;

import static dev.wasdevv.hdb.core.billing.BillingRuleViolation.Reason.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a finished encounter into an invoice draft, or says exactly why it cannot be billed.
 * Amounts are rounded per line (2 places, HALF_EVEN) and the total is the sum of the rounded lines,
 * which is how the ERP computes it too.
 */
public final class BillingRules {

    public static final String CURRENCY = "BRL";

    public InvoiceDraft draft(BillableEncounter e) {
        if (!"finished".equals(e.encounterStatus())) {
            throw new BillingRuleViolation(NOT_FINISHED, "encounter status is " + e.encounterStatus());
        }
        if (!e.coverageActive()) {
            throw new BillingRuleViolation(COVERAGE_INACTIVE, "coverage is not active");
        }
        if (e.customerCode() == null || e.customerCode().isBlank()) {
            throw new BillingRuleViolation(MISSING_PAYOR, "payor has no ERP customer code");
        }
        if (e.diagnosisCodes().isEmpty()) {
            throw new BillingRuleViolation(NO_DIAGNOSIS, "encounter has no ICD-10 diagnosis");
        }
        if (e.items().isEmpty()) {
            throw new BillingRuleViolation(NO_ITEMS, "claim has no items");
        }
        if (e.currency() != null && !CURRENCY.equals(e.currency())) {
            throw new BillingRuleViolation(UNSUPPORTED_CURRENCY, "claim currency is " + e.currency());
        }

        List<InvoiceDraft.Line> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (int i = 0; i < e.items().size(); i++) {
            BillableEncounter.ClaimItem item = e.items().get(i);
            if (item.quantity() == null || item.quantity().signum() <= 0) {
                throw new BillingRuleViolation(INVALID_ITEM, "item " + i + " needs a positive quantity");
            }
            if (item.unitPrice() == null || item.unitPrice().signum() < 0) {
                throw new BillingRuleViolation(INVALID_ITEM, "item " + i + " needs a unit price of zero or more");
            }
            if (item.currency() != null && !CURRENCY.equals(item.currency())) {
                throw new BillingRuleViolation(UNSUPPORTED_CURRENCY, "item " + i + " currency is " + item.currency());
            }
            BigDecimal amount = item.quantity().multiply(item.unitPrice()).setScale(2, RoundingMode.HALF_EVEN);
            lines.add(new InvoiceDraft.Line(item.code(), item.description(), item.quantity(),
                    item.unitPrice().setScale(2, RoundingMode.HALF_EVEN), amount));
            total = total.add(amount);
        }
        if (e.claimTotal() != null && e.claimTotal().setScale(2, RoundingMode.HALF_EVEN).compareTo(total) != 0) {
            throw new BillingRuleViolation(TOTAL_MISMATCH, "claim total " + e.claimTotal() + " but items add up to " + total);
        }
        return new InvoiceDraft(e.encounterId(), e.patientId(), e.customerCode(), e.serviceDate(), CURRENCY,
                e.diagnosisCodes(), lines, total);
    }
}
