package dev.wasdevv.hdb.fhir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.PreconditionFailedException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import dev.wasdevv.hdb.core.demo.DemoDataset;
import dev.wasdevv.hdb.core.fhir.Systems;
import dev.wasdevv.hdb.fhir.store.ResourceWriteListener;
import dev.wasdevv.hdb.fhir.validation.ResourceValidator;
import dev.wasdevv.hdb.testsupport.RequiresTestDatabase;
import dev.wasdevv.hdb.testsupport.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ExtendWith(RequiresTestDatabase.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FhirServerIT.ExplodingListener.class)
class FhirServerIT {

    static final String BOOM = "boom";

    /** Fails the write of any Encounter carrying identifier "boom": proves listeners share the write's transaction. */
    @TestConfiguration
    static class ExplodingListener {
        @Bean
        ResourceWriteListener exploding() {
            return resource -> {
                if (resource instanceof Encounter e && e.getIdentifier().stream().anyMatch(i -> BOOM.equals(i.getValue()))) {
                    throw new IllegalStateException("listener failed");
                }
            };
        }
    }

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry::add, "fhir_it");
    }

    @LocalServerPort
    int port;
    @Autowired
    FhirContext ctx;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ResourceValidator validator;

    IGenericClient client;
    final List<DemoDataset.DemoPatient> patients = DemoDataset.patients(3, 11);

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate fhir_resource cascade");
        ctx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
        client = ctx.newRestfulGenericClient(base());
    }

    String base() {
        return "http://localhost:" + port + "/fhir";
    }

    int count(String type) {
        return jdbc.queryForObject("select count(*) from fhir_resource where resource_type = ?", Integer.class, type);
    }

    DemoDataset.DemoEncounter encounter(String id, DemoDataset.DemoPatient p, int day, String icd10) {
        return new DemoDataset.DemoEncounter(id, p,
                LocalDate.of(2026, 10, day).atTime(10, 0).toInstant(ZoneOffset.UTC), icd10,
                List.of(DemoDataset.PROCEDURES.get(0), DemoDataset.PROCEDURES.get(1)));
    }

    @Test
    void metadataListsResourcesAndInteractionsAndIsValid() {
        CapabilityStatement cs = client.capabilities().ofType(CapabilityStatement.class).execute();

        assertThat(cs.getFhirVersion().toCode()).isEqualTo("4.0.1");
        var rest = cs.getRestFirstRep();
        assertThat(rest.getResource()).extracting(r -> r.getType())
                .contains("Patient", "Encounter", "Coverage", "Claim", "Condition", "Organization", "Location",
                        "Practitioner");
        var encounter = rest.getResource().stream().filter(r -> r.getType().equals("Encounter")).findFirst().orElseThrow();
        assertThat(encounter.getInteraction()).extracting(i -> i.getCode().toCode())
                .contains("read", "create", "update", "search-type");
        assertThat(encounter.getConditionalUpdate()).isTrue();
        assertThat(encounter.getSearchParam()).extracting(p -> p.getName()).contains("patient", "date", "status");
        assertThat(rest.getInteraction()).extracting(i -> i.getCode().toCode()).contains("transaction");
        assertThat(ResourceValidator.errors(validator.validateAgainst(cs,
                "http://hl7.org/fhir/StructureDefinition/CapabilityStatement"))).isEmpty();
    }

    @Test
    void validResourceIsStoredAndReadBackWithVersion() {
        MethodOutcome created = client.create().resource(DemoDataset.patient(patients.get(0))).execute();

        Patient read = client.read().resource(Patient.class).withId(created.getId().getIdPart()).execute();
        assertThat(read.getIdentifierFirstRep().getValue()).isEqualTo(patients.get(0).cpf());
        assertThat(read.getMeta().getVersionId()).isEqualTo("1");
        assertThat(read.getMeta().getLastUpdated()).isNotNull();
    }

    @Test
    void invalidResourceIsRejectedWithOperationOutcomeAndNotStored() throws Exception {
        String body = """
                {"resourceType":"Patient","gender":"banana","communication":[{"preferred":true}]}""";
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base() + "/Patient"))
                        .header("Content-Type", "application/fhir+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isIn(400, 422);
        OperationOutcome oo = ctx.newJsonParser().parseResource(OperationOutcome.class, response.body());
        assertThat(oo.getIssue()).isNotEmpty();
        assertThat(oo.getIssue()).extracting(i -> i.getDiagnostics()).anySatisfy(d -> assertThat(d).isNotBlank());
        assertThat(count("Patient")).isZero();
    }

    @Test
    void encounterWithoutStatusIsRejected() {
        Encounter e = (Encounter) DemoDataset.encounterBundle(encounter("enc-x", patients.get(0), 3, "I10"))
                .getEntry().getLast().getResource();
        e.setStatusElement(null);
        e.getDiagnosis().clear();

        assertThatThrownBy(() -> client.update().resource(e).withId("enc-x").execute())
                .isInstanceOf(UnprocessableEntityException.class);
        assertThat(count("Encounter")).isZero();
    }

    @Test
    void updateWithStaleIfMatchIsPreconditionFailed() {
        Patient p = DemoDataset.patient(patients.get(0));
        client.update().resource(p).withId("pat-01").execute();
        client.update().resource(p).withId("pat-01").execute();

        Patient stale = DemoDataset.patient(patients.get(0));
        stale.setId("Patient/pat-01/_history/1");
        assertThatThrownBy(() -> client.update().resource(stale).withId(stale.getIdElement()).execute())
                .isInstanceOf(PreconditionFailedException.class);

        stale.setId("Patient/pat-01/_history/2");
        MethodOutcome ok = client.update().resource(stale).withId(stale.getIdElement()).execute();
        assertThat(ok.getId().getVersionIdPart()).isEqualTo("3");
    }

    @Test
    void transactionWritesEveryEntryAndResolvesUrnReferences() {
        client.transaction().withBundle(DemoDataset.registryBundle(patients)).execute();
        Bundle response = client.transaction()
                .withBundle(DemoDataset.encounterBundle(encounter("enc-0001", patients.get(0), 3, "I10"))).execute();

        assertThat(response.getEntry()).extracting(e -> e.getResponse().getStatus())
                .containsExactly("201 Created", "201 Created", "201 Created");
        Encounter stored = client.read().resource(Encounter.class).withId("enc-0001").execute();
        String conditionRef = stored.getDiagnosisFirstRep().getCondition().getReference();
        assertThat(conditionRef).startsWith("Condition/").doesNotContain("urn:uuid");
        assertThat(client.read().resource("Condition").withId(conditionRef.substring("Condition/".length())).execute())
                .isNotNull();
    }

    @Test
    void transactionIsAtomicWhenOneEntryIsInvalid() {
        Bundle bundle = DemoDataset.registryBundle(patients);
        ((Patient) bundle.getEntry().get(2).getResource()).addCommunication().setPreferred(true); // language is 1..1

        assertThatThrownBy(() -> client.transaction().withBundle(bundle).execute())
                .isInstanceOf(UnprocessableEntityException.class)
                .satisfies(ex -> {
                    var oo = (OperationOutcome) ((BaseServerResponseException) ex).getOperationOutcome();
                    assertThat(oo.getIssueFirstRep().getDiagnostics()).contains("Bundle.entry[2]").contains("rolled back");
                });
        assertThat(count("Patient")).isZero();
        assertThat(count("Coverage")).isZero();
    }

    @Test
    void transactionRollsBackWhenAListenerFails() {
        client.transaction().withBundle(DemoDataset.registryBundle(patients)).execute();
        Bundle bundle = DemoDataset.encounterBundle(encounter("enc-0002", patients.get(0), 3, "I10"));
        ((Encounter) bundle.getEntry().getLast().getResource()).addIdentifier().setValue(BOOM);

        assertThatThrownBy(() -> client.transaction().withBundle(bundle).execute())
                .isInstanceOf(BaseServerResponseException.class);
        assertThat(count("Encounter")).isZero();
        assertThat(count("Condition")).isZero();
        assertThat(count("Claim")).isZero();
    }

    @Test
    void searchesByIdentifierPatientDateAndStatus() {
        client.transaction().withBundle(DemoDataset.registryBundle(patients)).execute();
        for (var e : List.of(encounter("enc-a", patients.get(0), 1, "I10"), encounter("enc-b", patients.get(0), 3, "J45.9"),
                encounter("enc-c", patients.get(1), 3, "A09"))) {
            client.transaction().withBundle(DemoDataset.encounterBundle(e)).execute();
        }

        Bundle byCpf = client.search().byUrl("Patient?identifier=" + Systems.CPF + "|" + patients.get(1).cpf())
                .returnBundle(Bundle.class).execute();
        assertThat(byCpf.getEntry()).extracting(e -> e.getResource().getIdElement().getIdPart()).containsExactly("pat-02");

        Bundle byValueOnly = client.search().byUrl("Patient?identifier=" + patients.get(2).cpf())
                .returnBundle(Bundle.class).execute();
        assertThat(byValueOnly.getEntry()).hasSize(1);

        Bundle encounters = client.search().byUrl("Encounter?patient=Patient/pat-01&date=2026-10-03")
                .returnBundle(Bundle.class).execute();
        assertThat(encounters.getEntry()).extracting(e -> e.getResource().getIdElement().getIdPart())
                .containsExactly("enc-b");

        Bundle range = client.search().byUrl("Encounter?date=ge2026-10-01&date=le2026-10-02")
                .returnBundle(Bundle.class).execute();
        assertThat(range.getEntry()).extracting(e -> e.getResource().getIdElement().getIdPart()).containsExactly("enc-a");
        assertThat(client.search().byUrl("Encounter?date=gt2026-10-01&date=lt2026-10-03").returnBundle(Bundle.class)
                .execute().getEntry()).isEmpty();
        assertThat(client.search().byUrl("Encounter?date=gt2026-10-02").returnBundle(Bundle.class)
                .execute().getEntry()).hasSize(2);

        Bundle claims = client.search().byUrl("Claim?status=active").returnBundle(Bundle.class).execute();
        assertThat(claims.getEntry()).hasSize(3);
        assertThat(client.search().byUrl("Claim?status=cancelled").returnBundle(Bundle.class).execute().getEntry()).isEmpty();

        Bundle claimOfEncounter = client.search().byUrl("Claim?encounter=Encounter/enc-c").returnBundle(Bundle.class).execute();
        assertThat(claimOfEncounter.getEntry()).hasSize(1);
        assertThat(((Claim) claimOfEncounter.getEntryFirstRep().getResource()).getPatient().getReference())
                .isEqualTo("Patient/pat-02");
    }

    @Test
    void searchPagesWithCountAndNextLinks() {
        var many = DemoDataset.patients(12, 5);
        client.transaction().withBundle(DemoDataset.registryBundle(many)).execute();

        Bundle page = client.search().byUrl("Patient?_count=5").returnBundle(Bundle.class).execute();
        assertThat(page.getTotal()).isEqualTo(12);
        int seen = page.getEntry().size();
        int pages = 1;
        assertThat(seen).isEqualTo(5);
        while (page.getLink(Bundle.LINK_NEXT) != null) {
            page = client.loadPage().next(page).execute();
            seen += page.getEntry().size();
            pages++;
        }
        assertThat(seen).isEqualTo(12);
        assertThat(pages).isEqualTo(3);
    }

    @Test
    void conditionalUpdateByIdentifierCreatesThenUpdatesTheSameResource() {
        Organization org = new Organization().setName("Operadora A");
        org.addIdentifier().setSystem(Systems.ERP_CUSTOMER).setValue("CUST-001");
        String url = "Organization?identifier=" + Systems.ERP_CUSTOMER + "|CUST-001";

        MethodOutcome first = client.update().resource(org).conditionalByUrl(url).execute();
        org.setName("Operadora A renomeada");
        MethodOutcome second = client.update().resource(org).conditionalByUrl(url).execute();

        assertThat(second.getId().getIdPart()).isEqualTo(first.getId().getIdPart());
        assertThat(count("Organization")).isEqualTo(1);
        assertThat(client.read().resource(Organization.class).withId(first.getId().getIdPart()).execute().getName())
                .isEqualTo("Operadora A renomeada");
    }

    @Test
    void conditionalUpdateMatchingSeveralResourcesIsPreconditionFailed() {
        Organization org = new Organization().setName("dup");
        org.addIdentifier().setSystem(Systems.ERP_CUSTOMER).setValue("CUST-DUP");
        client.create().resource(org).execute();
        client.create().resource(org).execute();

        assertThatThrownBy(() -> client.update().resource(org)
                .conditionalByUrl("Organization?identifier=" + Systems.ERP_CUSTOMER + "|CUST-DUP").execute())
                .isInstanceOf(PreconditionFailedException.class);
    }
}
