package dev.wasdevv.hdb.core.fhir;

import dev.wasdevv.hdb.core.billing.BillableEncounter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Money;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;

/**
 * Pulls the billing facts out of the FHIR resources around one encounter. It decides nothing:
 * {@link dev.wasdevv.hdb.core.billing.BillingRules} does. Missing data comes out as null or empty,
 * so the rule that cares about it is the one that rejects it.
 */
public final class BillableEncounterExtractor {

    /**
     * @param payor the Organization the coverage's payor points to, when the reference is literal.
     *              A logical reference ({@code payor.identifier}) needs no lookup.
     */
    public BillableEncounter extract(Encounter encounter, Coverage coverage, Claim claim,
                                     List<Condition> conditions, Optional<Organization> payor) {
        LocalDate serviceDate = toUtcDate(encounter.getPeriod().getStart());
        String status = encounter.hasStatus() ? encounter.getStatus().toCode() : null;
        String currency = claim.hasTotal() ? claim.getTotal().getCurrency() : null;

        List<String> diagnoses = conditions.stream()
                .flatMap(c -> c.getCode().getCoding().stream())
                .filter(coding -> Systems.ICD_10.equals(coding.getSystem()) && coding.hasCode())
                .map(Coding::getCode)
                .distinct()
                .toList();

        List<BillableEncounter.ClaimItem> items = claim.getItem().stream()
                .map(BillableEncounterExtractor::toItem)
                .toList();

        return new BillableEncounter(
                encounter.getIdElement().getIdPart(),
                status,
                encounter.getSubject().getReferenceElement().getIdPart(),
                serviceDate,
                coverageActiveOn(coverage, serviceDate),
                customerCode(coverage, payor),
                diagnoses,
                items,
                claim.hasTotal() ? claim.getTotal().getValue() : null,
                currency);
    }

    static boolean coverageActiveOn(Coverage coverage, LocalDate date) {
        if (coverage.getStatus() != Coverage.CoverageStatus.ACTIVE) {
            return false;
        }
        if (!coverage.hasPeriod() || date == null) {
            return true;
        }
        Period p = coverage.getPeriod();
        LocalDate start = toUtcDate(p.getStart());
        LocalDate end = toUtcDate(p.getEnd());
        return (start == null || !date.isBefore(start)) && (end == null || !date.isAfter(end));
    }

    static String customerCode(Coverage coverage, Optional<Organization> payor) {
        if (!coverage.hasPayor()) {
            return null;
        }
        Reference ref = coverage.getPayorFirstRep();
        if (ref.hasIdentifier() && Systems.ERP_CUSTOMER.equals(ref.getIdentifier().getSystem())) {
            return ref.getIdentifier().getValue();
        }
        return payor.flatMap(org -> org.getIdentifier().stream()
                        .filter(id -> Systems.ERP_CUSTOMER.equals(id.getSystem()))
                        .map(Identifier::getValue)
                        .findFirst())
                .orElse(null);
    }

    private static BillableEncounter.ClaimItem toItem(Claim.ItemComponent item) {
        Coding product = item.getProductOrService().getCodingFirstRep();
        Money unitPrice = item.getUnitPrice();
        BigDecimal quantity = item.hasQuantity() ? item.getQuantity().getValue() : null;
        return new BillableEncounter.ClaimItem(
                product.getCode(),
                product.hasDisplay() ? product.getDisplay() : item.getProductOrService().getText(),
                quantity,
                item.hasUnitPrice() ? unitPrice.getValue() : null,
                item.hasUnitPrice() ? unitPrice.getCurrency() : null);
    }

    static LocalDate toUtcDate(Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
    }
}
