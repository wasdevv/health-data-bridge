package dev.wasdevv.hdb.sync.outbox;

import ca.uhn.fhir.validation.ValidationResult;
import dev.wasdevv.hdb.core.billing.BillingRuleViolation;
import dev.wasdevv.hdb.core.billing.BillingRules;
import dev.wasdevv.hdb.core.erp.ErpInvoiceRequest;
import dev.wasdevv.hdb.core.erp.InvoiceRequestMapper;
import dev.wasdevv.hdb.core.fhir.BillableEncounterExtractor;
import dev.wasdevv.hdb.core.fhir.Systems;
import dev.wasdevv.hdb.fhir.store.ResourceStore;
import dev.wasdevv.hdb.fhir.store.SearchQuery;
import dev.wasdevv.hdb.fhir.validation.ResourceValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;

/**
 * Steps 4a to 4c of the PRD's main flow: gather the resources around a finished Encounter, validate it
 * against the billing profile, apply the billing rules and map to the ERP request.
 *
 * <p>A related resource that is not there yet (the Claim arrives after the Encounter) is retryable; a
 * profile or billing rule failure is not, because the same data fails the same way next time.
 */
public class InvoicePreparer {

    public static final class PreparationException extends RuntimeException {
        private final boolean permanent;

        PreparationException(boolean permanent, String reason) {
            super(reason);
            this.permanent = permanent;
        }

        public boolean permanent() {
            return permanent;
        }
    }

    private final ResourceStore store;
    private final ResourceValidator validator;
    private final BillableEncounterExtractor extractor = new BillableEncounterExtractor();
    private final BillingRules rules = new BillingRules();

    public InvoicePreparer(ResourceStore store, ResourceValidator validator) {
        this.store = store;
        this.validator = validator;
    }

    public ErpInvoiceRequest prepare(String encounterId) {
        Encounter encounter = store.find(Encounter.class, encounterId)
                .orElseThrow(() -> permanent("Encounter/" + encounterId + " not found"));

        ValidationResult profile = validator.validateAgainst(encounter, Systems.BILLABLE_ENCOUNTER_PROFILE);
        List<String> errors = ResourceValidator.errors(profile);
        if (!errors.isEmpty()) {
            throw permanent("not a billable-encounter: " + String.join("; ", errors));
        }

        required(Patient.class, encounter.getSubject(), "subject");
        List<Condition> conditions = new ArrayList<>();
        for (Encounter.DiagnosisComponent dx : encounter.getDiagnosis()) {
            conditions.add(required(Condition.class, dx.getCondition(), "diagnosis.condition"));
        }

        Claim claim = claimFor(encounterId);
        Reference coverageRef = claim.getInsurance().stream()
                .filter(Claim.InsuranceComponent::getFocal)
                .findFirst()
                .orElseGet(claim::getInsuranceFirstRep)
                .getCoverage();
        Coverage coverage = required(Coverage.class, coverageRef, "Claim.insurance.coverage");
        Optional<Organization> payor = Optional.empty();
        Reference payorRef = coverage.getPayorFirstRep();
        if (payorRef.hasReference() && "Organization".equals(payorRef.getReferenceElement().getResourceType())) {
            payor = store.find(Organization.class, payorRef.getReferenceElement().getIdPart());
        }

        try {
            var draft = rules.draft(extractor.extract(encounter, coverage, claim, conditions, payor));
            return InvoiceRequestMapper.INSTANCE.toRequest(draft);
        } catch (BillingRuleViolation e) {
            throw permanent(e.getMessage());
        }
    }

    private Claim claimFor(String encounterId) {
        List<Claim> active = store.search(Claim.class, SearchQuery.of("Claim").exact("encounter", "Encounter/" + encounterId))
                .stream()
                .filter(c -> c.getStatus() == Claim.ClaimStatus.ACTIVE)
                .toList();
        if (active.isEmpty()) {
            throw retryable("no active Claim for Encounter/" + encounterId + " yet");
        }
        if (active.size() > 1) {
            throw permanent(active.size() + " active Claims for Encounter/" + encounterId + "; cannot pick one");
        }
        return active.get(0);
    }

    private <T extends Resource> T required(Class<T> type, Reference ref, String path) {
        if (!ref.hasReference()) {
            throw permanent(path + " has no literal reference");
        }
        String id = ref.getReferenceElement().getIdPart();
        return store.find(type, id).orElseThrow(() -> retryable(path + " -> " + type.getSimpleName() + "/" + id + " not found yet"));
    }

    private static PreparationException permanent(String reason) {
        return new PreparationException(true, reason);
    }

    private static PreparationException retryable(String reason) {
        return new PreparationException(false, reason);
    }
}
