package dev.wasdevv.hdb.core.billing;

/**
 * The encounter cannot be billed as it is. Retrying does not help: the data has to change first,
 * so the sync worker sends it straight to the dead letter queue.
 */
public class BillingRuleViolation extends RuntimeException {

    public enum Reason {
        NOT_FINISHED,
        COVERAGE_INACTIVE,
        MISSING_PAYOR,
        NO_DIAGNOSIS,
        NO_ITEMS,
        INVALID_ITEM,
        UNSUPPORTED_CURRENCY,
        TOTAL_MISMATCH
    }

    private final Reason reason;

    public BillingRuleViolation(Reason reason, String detail) {
        super(reason + ": " + detail);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
