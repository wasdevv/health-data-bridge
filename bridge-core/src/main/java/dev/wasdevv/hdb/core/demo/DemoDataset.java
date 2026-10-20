package dev.wasdevv.hdb.core.demo;

import dev.wasdevv.hdb.core.fhir.Systems;
import dev.wasdevv.hdb.core.patient.Cpf;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Money;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;

/**
 * Fictitious, deterministic demo data: the Compose seed and the tests both build their FHIR resources
 * here. Names are combinations of common first and last names, CPFs come from {@link Cpf#generate}
 * with a fixed seed, and procedure codes use a local example system, not TUSS.
 */
public final class DemoDataset {

    public static final String PROCEDURE_SYSTEM = "https://health-data-bridge.example/fhir/CodeSystem/procedure";
    public static final List<String> CUSTOMER_CODES = List.of("CUST-001", "CUST-002", "CUST-003", "CUST-004");

    private static final String[] GIVEN = {"Ana", "Bruno", "Carla", "Diego", "Elisa", "Fábio", "Gabriela", "Heitor",
            "Isabela", "João", "Larissa", "Marcos", "Natália", "Otávio", "Paula", "Rafael", "Sofia", "Tiago",
            "Valéria", "Yuri"};
    private static final String[] FAMILY = {"Almeida", "Barbosa", "Cardoso", "Duarte", "Esteves", "Ferraz",
            "Gonçalves", "Holanda", "Lacerda", "Moreira", "Nogueira", "Pacheco", "Queiroz", "Rezende"};

    record Icd10(String code, String display) {
    }

    private static final List<Icd10> DIAGNOSES = List.of(
            new Icd10("J06.9", "Infecção aguda das vias aéreas superiores não especificada"),
            new Icd10("I10", "Hipertensão essencial (primária)"),
            new Icd10("E11.9", "Diabetes mellitus não-insulino-dependente sem complicações"),
            new Icd10("M54.5", "Dor lombar baixa"),
            new Icd10("K29.7", "Gastrite não especificada"),
            new Icd10("N39.0", "Infecção do trato urinário de localização não especificada"),
            new Icd10("J45.9", "Asma não especificada"),
            new Icd10("A09", "Diarreia e gastroenterite de origem infecciosa presumível"));

    public record Procedure(String code, String display, BigDecimal unitPrice) {
    }

    public static final List<Procedure> PROCEDURES = List.of(
            new Procedure("CONS-01", "Consulta em consultório", new BigDecimal("150.00")),
            new Procedure("LAB-HEMO", "Hemograma completo", new BigDecimal("12.50")),
            new Procedure("LAB-GLIC", "Glicemia de jejum", new BigDecimal("8.90")),
            new Procedure("IMG-RX", "Radiografia de tórax", new BigDecimal("65.00")),
            new Procedure("MED-01", "Medicação administrada", new BigDecimal("23.40")));

    public record DemoPatient(String id, String cpf, String given, String family, LocalDate birthDate,
                              String customerCode) {

        public String coverageId() {
            return "cov-" + id.substring("pat-".length());
        }
    }

    public record DemoEncounter(String id, DemoPatient patient, Instant start, String icd10Code,
                                List<Procedure> procedures) {

        public BigDecimal total() {
            return procedures.stream().map(Procedure::unitPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private DemoDataset() {
    }

    public static List<DemoPatient> patients(int count, long seed) {
        Random random = new Random(seed);
        List<DemoPatient> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String given = GIVEN[(i - 1) % GIVEN.length];
            String family = FAMILY[random.nextInt(FAMILY.length)] + " " + FAMILY[random.nextInt(FAMILY.length)];
            LocalDate birth = LocalDate.of(1950 + random.nextInt(55), 1 + random.nextInt(12), 1 + random.nextInt(28));
            out.add(new DemoPatient("pat-%02d".formatted(i), Cpf.generate(random), given, family, birth,
                    CUSTOMER_CODES.get((i - 1) % CUSTOMER_CODES.size())));
        }
        return out;
    }

    /**
     * {@code count} finished encounters spread over {@code days} days from {@code firstDay}, round-robin
     * over the patients, between 08:00 and 17:00 UTC so the UTC day is never ambiguous.
     */
    public static List<DemoEncounter> encounters(List<DemoPatient> patients, int count, LocalDate firstDay, int days,
                                                 long seed) {
        Random random = new Random(seed);
        List<DemoEncounter> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            LocalDate day = firstDay.plusDays((i - 1) % days);
            Instant start = day.atTime(8 + random.nextInt(9), random.nextInt(60)).toInstant(ZoneOffset.UTC);
            List<Procedure> procedures = new ArrayList<>();
            procedures.add(PROCEDURES.get(0));
            int extra = random.nextInt(3);
            for (int k = 0; k < extra; k++) {
                procedures.add(PROCEDURES.get(1 + random.nextInt(PROCEDURES.size() - 1)));
            }
            out.add(new DemoEncounter("enc-%04d".formatted(i), patients.get((i - 1) % patients.size()), start,
                    DIAGNOSES.get(random.nextInt(DIAGNOSES.size())).code(), procedures));
        }
        return out;
    }

    /** Patients and their coverages, as PUTs with fixed ids: running it twice changes nothing but versions. */
    public static Bundle registryBundle(List<DemoPatient> patients) {
        Bundle bundle = new Bundle().setType(Bundle.BundleType.TRANSACTION);
        for (DemoPatient p : patients) {
            put(bundle, patient(p));
            put(bundle, coverage(p));
        }
        return bundle;
    }

    public static Patient patient(DemoPatient p) {
        Patient patient = new Patient();
        patient.setId(p.id());
        patient.addIdentifier().setSystem(Systems.CPF).setValue(p.cpf());
        patient.addName().setFamily(p.family()).addGiven(p.given());
        patient.setGender(p.given().endsWith("a") ? Enumerations.AdministrativeGender.FEMALE
                : Enumerations.AdministrativeGender.MALE);
        patient.setBirthDate(Date.from(p.birthDate().atStartOfDay(ZoneOffset.UTC).toInstant()));
        return patient;
    }

    public static Coverage coverage(DemoPatient p) {
        Coverage coverage = new Coverage();
        coverage.setId(p.coverageId());
        coverage.setStatus(Coverage.CoverageStatus.ACTIVE);
        coverage.setBeneficiary(new Reference("Patient/" + p.id()));
        coverage.addPayor(new Reference()
                .setIdentifier(new Identifier().setSystem(Systems.ERP_CUSTOMER).setValue(p.customerCode())));
        coverage.setPeriod(new Period().setStart(Date.from(Instant.parse("2026-01-01T00:00:00Z"))));
        return coverage;
    }

    /**
     * One encounter as a transaction: Condition and Claim as POSTs (referenced by {@code urn:uuid:}),
     * the Encounter as a PUT with its fixed id. With {@code icd10Code} null there is no diagnosis, which
     * FHIR accepts and billing does not: the dead letter path.
     */
    public static Bundle encounterBundle(DemoEncounter e) {
        Bundle bundle = new Bundle().setType(Bundle.BundleType.TRANSACTION);
        Encounter encounter = new Encounter();
        encounter.setId(e.id());
        encounter.setStatus(Encounter.EncounterStatus.FINISHED);
        encounter.getClass_().setSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode").setCode("AMB")
                .setDisplay("ambulatory");
        encounter.setSubject(new Reference("Patient/" + e.patient().id()));
        encounter.setPeriod(new Period().setStart(Date.from(e.start()))
                .setEnd(Date.from(e.start().plusSeconds(1800))));
        encounter.setServiceProvider(new Reference().setDisplay("Hospital Exemplo (fictício)"));

        if (e.icd10Code() != null) {
            String conditionUrl = "urn:uuid:" + UUID.nameUUIDFromBytes(("cond-" + e.id()).getBytes());
            Icd10 dx = DIAGNOSES.stream().filter(d -> d.code().equals(e.icd10Code())).findFirst()
                    .orElse(new Icd10(e.icd10Code(), null));
            Condition condition = new Condition();
            condition.getClinicalStatus().addCoding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/condition-clinical").setCode("active");
            condition.addCategory().addCoding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/condition-category")
                    .setCode("encounter-diagnosis");
            condition.getCode().addCoding().setSystem(Systems.ICD_10).setCode(dx.code()).setDisplay(dx.display());
            condition.setSubject(new Reference("Patient/" + e.patient().id()));
            condition.setEncounter(new Reference("Encounter/" + e.id()));
            bundle.addEntry().setFullUrl(conditionUrl).setResource(condition).getRequest()
                    .setMethod(Bundle.HTTPVerb.POST).setUrl("Condition");
            encounter.addDiagnosis().setCondition(new Reference(conditionUrl)).setRank(1);
        }

        bundle.addEntry().setFullUrl("urn:uuid:" + UUID.nameUUIDFromBytes(("claim-" + e.id()).getBytes()))
                .setResource(claim(e)).getRequest().setMethod(Bundle.HTTPVerb.POST).setUrl("Claim");
        put(bundle, encounter);
        return bundle;
    }

    public static Claim claim(DemoEncounter e) {
        Claim claim = new Claim();
        claim.setStatus(Claim.ClaimStatus.ACTIVE);
        claim.setType(new CodeableConcept().addCoding(
                new org.hl7.fhir.r4.model.Coding("http://terminology.hl7.org/CodeSystem/claim-type", "institutional",
                        "Institutional")));
        claim.setUse(Claim.Use.CLAIM);
        claim.setPatient(new Reference("Patient/" + e.patient().id()));
        claim.setCreated(Date.from(e.start().plusSeconds(1800)));
        claim.setProvider(new Reference().setDisplay("Hospital Exemplo (fictício)"));
        claim.getPriority().addCoding().setSystem("http://terminology.hl7.org/CodeSystem/processpriority")
                .setCode("normal");
        claim.addInsurance().setSequence(1).setFocal(true)
                .setCoverage(new Reference("Coverage/" + e.patient().coverageId()));
        int seq = 1;
        for (Procedure p : e.procedures()) {
            Claim.ItemComponent item = claim.addItem().setSequence(seq++);
            item.getProductOrService().addCoding().setSystem(PROCEDURE_SYSTEM).setCode(p.code()).setDisplay(p.display());
            item.getQuantity().setValue(1);
            item.setUnitPrice(new Money().setValue(p.unitPrice()).setCurrency("BRL"));
            item.setNet(new Money().setValue(p.unitPrice()).setCurrency("BRL"));
            item.addEncounter(new Reference("Encounter/" + e.id()));
        }
        claim.setTotal(new Money().setValue(e.total()).setCurrency("BRL"));
        return claim;
    }

    private static void put(Bundle bundle, Resource resource) {
        String url = resource.fhirType() + "/" + resource.getIdElement().getIdPart();
        bundle.addEntry().setFullUrl(url).setResource(resource).getRequest().setMethod(Bundle.HTTPVerb.PUT).setUrl(url);
    }
}
