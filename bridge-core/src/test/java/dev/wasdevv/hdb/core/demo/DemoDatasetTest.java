package dev.wasdevv.hdb.core.demo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.wasdevv.hdb.core.billing.BillingRules;
import dev.wasdevv.hdb.core.billing.InvoiceDraft;
import dev.wasdevv.hdb.core.fhir.BillableEncounterExtractor;
import dev.wasdevv.hdb.core.fhir.Systems;
import dev.wasdevv.hdb.core.patient.Cpf;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;

class DemoDatasetTest {

    final List<DemoDataset.DemoPatient> patients = DemoDataset.patients(20, 2026);

    @Test
    void patientsAreDeterministicWithValidSyntheticCpfs() {
        assertThat(patients).hasSize(20);
        assertThat(DemoDataset.patients(20, 2026)).isEqualTo(patients);
        assertThat(patients).allSatisfy(p -> assertThat(Cpf.isValid(p.cpf())).isTrue());
        assertThat(patients).extracting(DemoDataset.DemoPatient::cpf).doesNotHaveDuplicates();
        assertThat(patients).extracting(DemoDataset.DemoPatient::customerCode)
                .containsOnlyElementsOf(DemoDataset.CUSTOMER_CODES);
        assertThat(patients.get(0).id()).isEqualTo("pat-01");
        assertThat(patients.get(0).coverageId()).isEqualTo("cov-01");
    }

    @Test
    void encountersAreSpreadOverDaysInsideUtcWorkingHours() {
        var encounters = DemoDataset.encounters(patients, 50, LocalDate.of(2026, 10, 1), 5, 7);

        assertThat(encounters).hasSize(50);
        assertThat(encounters).extracting(e -> e.start().atZone(ZoneOffset.UTC).toLocalDate()).containsOnly(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3),
                LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 5));
        assertThat(encounters).allSatisfy(e -> {
            assertThat(e.start().atZone(ZoneOffset.UTC).getHour()).isBetween(8, 16);
            assertThat(e.procedures()).isNotEmpty();
            assertThat(e.icd10Code()).isNotNull();
        });
    }

    @Test
    void registryBundlePutsPatientsAndCoverages() {
        Bundle bundle = DemoDataset.registryBundle(patients.subList(0, 2));

        assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.TRANSACTION);
        assertThat(bundle.getEntry()).extracting(e -> e.getRequest().getUrl())
                .containsExactly("Patient/pat-01", "Coverage/cov-01", "Patient/pat-02", "Coverage/cov-02");
        Patient p = (Patient) bundle.getEntry().get(0).getResource();
        assertThat(p.getIdentifierFirstRep().getSystem()).isEqualTo(Systems.CPF);
        Coverage c = (Coverage) bundle.getEntry().get(1).getResource();
        assertThat(c.getPayorFirstRep().getIdentifier().getValue()).isEqualTo(patients.get(0).customerCode());
    }

    @Test
    void encounterBundleBillsToTheClaimTotal() {
        var e = DemoDataset.encounters(patients, 1, LocalDate.of(2026, 10, 1), 1, 3).get(0);
        Bundle bundle = DemoDataset.encounterBundle(e);

        assertThat(bundle.getEntry()).extracting(en -> en.getResource().fhirType())
                .containsExactly("Condition", "Claim", "Encounter");
        Condition condition = (Condition) bundle.getEntry().get(0).getResource();
        Claim claim = (Claim) bundle.getEntry().get(1).getResource();
        Encounter encounter = (Encounter) bundle.getEntry().get(2).getResource();
        assertThat(encounter.getDiagnosisFirstRep().getCondition().getReference())
                .isEqualTo(bundle.getEntry().get(0).getFullUrl());

        InvoiceDraft draft = new BillingRules().draft(new BillableEncounterExtractor().extract(encounter,
                DemoDataset.coverage(e.patient()), claim, List.of(condition), Optional.empty()));

        assertThat(draft.total()).isEqualByComparingTo(e.total());
        assertThat(draft.diagnosisCodes()).containsExactly(e.icd10Code());
        assertThat(draft.customerCode()).isEqualTo(e.patient().customerCode());
    }

    @Test
    void encounterWithoutDiagnosisHasNoCondition() {
        var base = DemoDataset.encounters(patients, 1, LocalDate.of(2026, 10, 1), 1, 3).get(0);
        var e = new DemoDataset.DemoEncounter(base.id(), base.patient(), base.start(), null, base.procedures());

        Bundle bundle = DemoDataset.encounterBundle(e);

        assertThat(bundle.getEntry()).extracting(en -> en.getResource().fhirType()).containsExactly("Claim", "Encounter");
        assertThat(((Encounter) bundle.getEntry().get(1).getResource()).hasDiagnosis()).isFalse();
    }
}
