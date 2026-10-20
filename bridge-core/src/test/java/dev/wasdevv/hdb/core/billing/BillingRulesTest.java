package dev.wasdevv.hdb.core.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.wasdevv.hdb.core.billing.BillingRuleViolation.Reason;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class BillingRulesTest {

    private final BillingRules rules = new BillingRules();

    static BillableEncounter encounter() {
        return new BillableEncounter("enc-1", "finished", "pat-1", LocalDate.of(2026, 10, 3), true, "CUST-001",
                List.of("J06.9"),
                List.of(new BillableEncounter.ClaimItem("10101012", "Consulta em consultório", new BigDecimal("1"),
                                new BigDecimal("150.00"), "BRL"),
                        new BillableEncounter.ClaimItem("40304361", "Hemograma completo", new BigDecimal("2"),
                                new BigDecimal("12.335"), "BRL")),
                new BigDecimal("174.67"), "BRL");
    }

    static BillableEncounter with(BillableEncounter e, java.util.function.UnaryOperator<Builder> change) {
        return change.apply(new Builder(e)).build();
    }

    @Test
    void draftsInvoiceWithRoundedLinesAndTheirSum() {
        InvoiceDraft draft = rules.draft(encounter());

        assertThat(draft.encounterId()).isEqualTo("enc-1");
        assertThat(draft.patientId()).isEqualTo("pat-1");
        assertThat(draft.customerCode()).isEqualTo("CUST-001");
        assertThat(draft.issueDate()).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(draft.currency()).isEqualTo("BRL");
        assertThat(draft.lines()).extracting(InvoiceDraft.Line::amount)
                .containsExactly(new BigDecimal("150.00"), new BigDecimal("24.67"));
        assertThat(draft.total()).isEqualByComparingTo("174.67");
    }

    @Test
    void halfEvenRoundingPerLine() {
        BillableEncounter e = with(encounter(), b -> b.items(List.of(
                new BillableEncounter.ClaimItem("A", "a", BigDecimal.ONE, new BigDecimal("0.125"), "BRL"),
                new BillableEncounter.ClaimItem("B", "b", BigDecimal.ONE, new BigDecimal("0.135"), "BRL")))
                .claimTotal(null));

        InvoiceDraft draft = rules.draft(e);

        assertThat(draft.lines()).extracting(InvoiceDraft.Line::amount)
                .containsExactly(new BigDecimal("0.12"), new BigDecimal("0.14"));
        assertThat(draft.total()).isEqualByComparingTo("0.26");
    }

    @Test
    void claimTotalIsOptional() {
        assertThat(rules.draft(with(encounter(), b -> b.claimTotal(null))).total()).isEqualByComparingTo("174.67");
    }

    @Test
    void rejectsEncounterThatIsNotFinished() {
        assertViolation(with(encounter(), b -> b.status("in-progress")), Reason.NOT_FINISHED);
    }

    @Test
    void rejectsInactiveCoverage() {
        assertViolation(with(encounter(), b -> b.coverageActive(false)), Reason.COVERAGE_INACTIVE);
    }

    @Test
    void rejectsMissingPayor() {
        assertViolation(with(encounter(), b -> b.customerCode(null)), Reason.MISSING_PAYOR);
        assertViolation(with(encounter(), b -> b.customerCode(" ")), Reason.MISSING_PAYOR);
    }

    @Test
    void rejectsEncounterWithoutDiagnosis() {
        assertViolation(with(encounter(), b -> b.diagnoses(List.of())), Reason.NO_DIAGNOSIS);
    }

    @Test
    void rejectsClaimWithoutItems() {
        assertViolation(with(encounter(), b -> b.items(List.of())), Reason.NO_ITEMS);
    }

    @Test
    void rejectsZeroQuantityAndNegativePrice() {
        assertViolation(with(encounter(), b -> b.items(List.of(
                new BillableEncounter.ClaimItem("A", "a", BigDecimal.ZERO, BigDecimal.TEN, "BRL")))), Reason.INVALID_ITEM);
        assertViolation(with(encounter(), b -> b.items(List.of(
                new BillableEncounter.ClaimItem("A", "a", null, BigDecimal.TEN, "BRL")))), Reason.INVALID_ITEM);
        assertViolation(with(encounter(), b -> b.items(List.of(
                new BillableEncounter.ClaimItem("A", "a", BigDecimal.ONE, new BigDecimal("-1"), "BRL")))), Reason.INVALID_ITEM);
        assertViolation(with(encounter(), b -> b.items(List.of(
                new BillableEncounter.ClaimItem("A", "a", BigDecimal.ONE, null, "BRL")))), Reason.INVALID_ITEM);
    }

    @Test
    void freeItemIsAllowed() {
        InvoiceDraft draft = rules.draft(with(encounter(), b -> b.items(List.of(
                new BillableEncounter.ClaimItem("A", "retorno", BigDecimal.ONE, BigDecimal.ZERO, "BRL"))).claimTotal(null)));
        assertThat(draft.total()).isEqualByComparingTo("0");
    }

    @Test
    void rejectsOtherCurrencies() {
        assertViolation(with(encounter(), b -> b.currency("USD")), Reason.UNSUPPORTED_CURRENCY);
        assertViolation(with(encounter(), b -> b.items(List.of(
                new BillableEncounter.ClaimItem("A", "a", BigDecimal.ONE, BigDecimal.TEN, "USD"))).claimTotal(null)),
                Reason.UNSUPPORTED_CURRENCY);
    }

    @Test
    void rejectsClaimTotalThatDisagreesWithItems() {
        assertViolation(with(encounter(), b -> b.claimTotal(new BigDecimal("174.66"))), Reason.TOTAL_MISMATCH);
    }

    private void assertViolation(BillableEncounter e, Reason reason) {
        assertThatThrownBy(() -> rules.draft(e))
                .isInstanceOf(BillingRuleViolation.class)
                .satisfies(ex -> assertThat(((BillingRuleViolation) ex).reason()).isEqualTo(reason));
    }

    static final class Builder {
        private String status;
        private boolean coverageActive;
        private String customerCode;
        private List<String> diagnoses;
        private List<BillableEncounter.ClaimItem> items;
        private BigDecimal claimTotal;
        private String currency;
        private final BillableEncounter base;

        Builder(BillableEncounter e) {
            base = e;
            status = e.encounterStatus();
            coverageActive = e.coverageActive();
            customerCode = e.customerCode();
            diagnoses = e.diagnosisCodes();
            items = e.items();
            claimTotal = e.claimTotal();
            currency = e.currency();
        }

        Builder status(String v) { status = v; return this; }
        Builder coverageActive(boolean v) { coverageActive = v; return this; }
        Builder customerCode(String v) { customerCode = v; return this; }
        Builder diagnoses(List<String> v) { diagnoses = v; return this; }
        Builder items(List<BillableEncounter.ClaimItem> v) { items = v; return this; }
        Builder claimTotal(BigDecimal v) { claimTotal = v; return this; }
        Builder currency(String v) { currency = v; return this; }

        BillableEncounter build() {
            return new BillableEncounter(base.encounterId(), status, base.patientId(), base.serviceDate(), coverageActive,
                    customerCode, diagnoses, items, claimTotal, currency);
        }
    }
}
