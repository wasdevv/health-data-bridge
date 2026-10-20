package dev.wasdevv.hdb.core.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import dev.wasdevv.hdb.core.billing.BillableEncounter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Money;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.Test;

class BillableEncounterExtractorTest {

    private final BillableEncounterExtractor extractor = new BillableEncounterExtractor();

    static Date utc(int y, int m, int d, int h) {
        return Date.from(LocalDate.of(y, m, d).atTime(h, 0).toInstant(ZoneOffset.UTC));
    }

    static Encounter encounter() {
        Encounter e = new Encounter();
        e.setId("enc-1");
        e.setStatus(Encounter.EncounterStatus.FINISHED);
        e.setSubject(new Reference("Patient/pat-1"));
        e.setPeriod(new Period().setStart(utc(2026, 10, 3, 23)).setEnd(utc(2026, 10, 4, 1)));
        return e;
    }

    static Coverage coverage() {
        Coverage c = new Coverage();
        c.setStatus(Coverage.CoverageStatus.ACTIVE);
        c.addPayor(new Reference().setIdentifier(new Identifier().setSystem(Systems.ERP_CUSTOMER).setValue("CUST-001")));
        return c;
    }

    static Claim claim() {
        Claim claim = new Claim();
        Claim.ItemComponent item = claim.addItem();
        item.getProductOrService().addCoding().setCode("10101012").setDisplay("Consulta");
        item.getQuantity().setValue(1);
        item.setUnitPrice(new Money().setValue(new BigDecimal("150.00")).setCurrency("BRL"));
        Claim.ItemComponent textOnly = claim.addItem();
        textOnly.getProductOrService().setText("Curativo").addCoding().setCode("X1");
        claim.setTotal(new Money().setValue(new BigDecimal("150.00")).setCurrency("BRL"));
        return claim;
    }

    static Condition condition(String system, String code) {
        Condition c = new Condition();
        c.getCode().addCoding().setSystem(system).setCode(code);
        return c;
    }

    @Test
    void extractsBillingFacts() {
        BillableEncounter b = extractor.extract(encounter(), coverage(), claim(),
                List.of(condition(Systems.ICD_10, "J06.9"), condition("http://snomed.info/sct", "54150009"),
                        condition(Systems.ICD_10, "J06.9")),
                Optional.empty());

        assertThat(b.encounterId()).isEqualTo("enc-1");
        assertThat(b.encounterStatus()).isEqualTo("finished");
        assertThat(b.patientId()).isEqualTo("pat-1");
        assertThat(b.serviceDate()).as("UTC date of period.start").isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(b.coverageActive()).isTrue();
        assertThat(b.customerCode()).isEqualTo("CUST-001");
        assertThat(b.diagnosisCodes()).containsExactly("J06.9");
        assertThat(b.items()).hasSize(2);
        assertThat(b.items().get(0).description()).isEqualTo("Consulta");
        assertThat(b.items().get(1).description()).isEqualTo("Curativo");
        assertThat(b.items().get(1).quantity()).isNull();
        assertThat(b.items().get(1).unitPrice()).isNull();
        assertThat(b.claimTotal()).isEqualByComparingTo("150.00");
        assertThat(b.currency()).isEqualTo("BRL");
    }

    @Test
    void customerCodeFallsBackToResolvedPayorOrganization() {
        Coverage c = new Coverage().setStatus(Coverage.CoverageStatus.ACTIVE);
        c.addPayor(new Reference("Organization/org-1"));
        Organization org = new Organization();
        org.addIdentifier().setSystem(Systems.CNPJ).setValue("11222333000181");
        org.addIdentifier().setSystem(Systems.ERP_CUSTOMER).setValue("CUST-002");

        assertThat(BillableEncounterExtractor.customerCode(c, Optional.of(org))).isEqualTo("CUST-002");
        assertThat(BillableEncounterExtractor.customerCode(c, Optional.empty())).isNull();
        assertThat(BillableEncounterExtractor.customerCode(new Coverage(), Optional.of(org))).isNull();
    }

    @Test
    void coverageMustBeActiveAndCoverTheServiceDate() {
        LocalDate day = LocalDate.of(2026, 10, 3);
        Coverage c = coverage();
        assertThat(BillableEncounterExtractor.coverageActiveOn(c, day)).isTrue();

        c.setPeriod(new Period().setStart(utc(2026, 1, 1, 0)).setEnd(utc(2026, 10, 2, 0)));
        assertThat(BillableEncounterExtractor.coverageActiveOn(c, day)).isFalse();

        c.setPeriod(new Period().setStart(utc(2026, 10, 4, 0)));
        assertThat(BillableEncounterExtractor.coverageActiveOn(c, day)).isFalse();

        c.setPeriod(new Period().setStart(utc(2026, 10, 3, 0)).setEnd(utc(2026, 10, 3, 0)));
        assertThat(BillableEncounterExtractor.coverageActiveOn(c, day)).isTrue();

        c.setStatus(Coverage.CoverageStatus.CANCELLED);
        assertThat(BillableEncounterExtractor.coverageActiveOn(c, day)).isFalse();
    }

    @Test
    void missingTotalAndStatusComeOutAsNull() {
        Encounter e = encounter();
        e.setStatusElement(null);
        Claim claim = claim();
        claim.setTotal(null);

        BillableEncounter b = extractor.extract(e, coverage(), claim, List.of(), Optional.empty());

        assertThat(b.encounterStatus()).isNull();
        assertThat(b.claimTotal()).isNull();
        assertThat(b.currency()).isNull();
        assertThat(b.diagnosisCodes()).isEmpty();
    }
}
