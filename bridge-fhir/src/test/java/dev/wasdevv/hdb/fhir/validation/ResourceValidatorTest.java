package dev.wasdevv.hdb.fhir.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.validation.ValidationResult;
import dev.wasdevv.hdb.core.demo.DemoDataset;
import dev.wasdevv.hdb.core.fhir.Systems;
import java.time.LocalDate;
import java.util.List;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ResourceValidatorTest {

    static ResourceValidator validator;
    static final List<DemoDataset.DemoPatient> PATIENTS = DemoDataset.patients(2, 1);

    @BeforeAll
    static void init() {
        validator = new ResourceValidator(FhirContext.forR4Cached());
    }

    static Encounter encounter(String icd10) {
        var e = new DemoDataset.DemoEncounter("enc-1", PATIENTS.get(0),
                LocalDate.of(2026, 10, 3).atTime(10, 0).toInstant(java.time.ZoneOffset.UTC), icd10,
                List.of(DemoDataset.PROCEDURES.get(0)));
        Bundle bundle = DemoDataset.encounterBundle(e);
        return (Encounter) bundle.getEntry().getLast().getResource();
    }

    @Test
    void everyDemoResourceIsValidR4() {
        var encounters = DemoDataset.encounters(PATIENTS, 3, LocalDate.of(2026, 10, 1), 2, 9);
        List<Resource> resources = new java.util.ArrayList<>();
        DemoDataset.registryBundle(PATIENTS).getEntry().forEach(en -> resources.add(en.getResource()));
        encounters.forEach(e -> DemoDataset.encounterBundle(e).getEntry().forEach(en -> resources.add(en.getResource())));

        for (Resource r : resources) {
            validator.validateOrThrow(r);
        }
    }

    @Test
    void invalidResourceIsRejectedWithOperationOutcome() {
        Patient patient = DemoDataset.patient(PATIENTS.get(0));
        patient.addCommunication().setPreferred(true); // Patient.communication.language is 1..1

        assertThatThrownBy(() -> validator.validateOrThrow(patient))
                .isInstanceOf(UnprocessableEntityException.class)
                .satisfies(ex -> {
                    var oo = (OperationOutcome) ((UnprocessableEntityException) ex).getOperationOutcome();
                    assertThat(oo.getIssue()).anySatisfy(i ->
                            assertThat(i.getSeverity()).isIn(OperationOutcome.IssueSeverity.ERROR,
                                    OperationOutcome.IssueSeverity.FATAL));
                });
    }

    @Test
    void encounterWithoutStatusIsInvalidR4() {
        Encounter e = encounter("I10");
        e.setStatusElement(null);
        assertThatThrownBy(() -> validator.validateOrThrow(e)).isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void billableProfileAcceptsEncounterWithDiagnosis() {
        ValidationResult result = validator.validateAgainst(encounter("I10"), Systems.BILLABLE_ENCOUNTER_PROFILE);
        assertThat(ResourceValidator.errors(result)).isEmpty();
    }

    @Test
    void billableProfileRejectsEncounterWithoutDiagnosisThatBaseR4Accepts() {
        Encounter e = encounter(null);
        validator.validateOrThrow(e);

        ValidationResult result = validator.validateAgainst(e, Systems.BILLABLE_ENCOUNTER_PROFILE);

        assertThat(ResourceValidator.errors(result)).anySatisfy(m -> assertThat(m).contains("Encounter.diagnosis"));
    }

    @Test
    void billableProfileRejectsEncounterWithoutPeriodStart() {
        Encounter e = encounter("I10");
        e.getPeriod().setStartElement(null);

        assertThat(ResourceValidator.errors(validator.validateAgainst(e, Systems.BILLABLE_ENCOUNTER_PROFILE)))
                .anySatisfy(m -> assertThat(m).contains("Encounter.period.start"));
    }

    @Test
    void declaredProfileIsEnforcedOnWrite() {
        Encounter e = encounter(null);
        e.getMeta().addProfile(Systems.BILLABLE_ENCOUNTER_PROFILE);
        assertThatThrownBy(() -> validator.validateOrThrow(e)).isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void profileIsPublishedWithItsUrl() {
        assertThat(validator.billableEncounterProfile().getUrl()).isEqualTo(Systems.BILLABLE_ENCOUNTER_PROFILE);
    }
}
