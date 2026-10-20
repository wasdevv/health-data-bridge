package dev.wasdevv.hdb.sync.erp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.report.ValidationReport;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import dev.wasdevv.hdb.core.billing.BillingRules;
import dev.wasdevv.hdb.core.demo.DemoDataset;
import dev.wasdevv.hdb.core.erp.ErpInvoiceRequest;
import dev.wasdevv.hdb.core.erp.InvoiceRequestMapper;
import dev.wasdevv.hdb.core.fhir.BillableEncounterExtractor;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Encounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The bridge's side of the ERP contract. The OpenAPI file is the one erp-mock serves and tests against
 * ({@code static/openapi.yaml} from the erp-mock jar), so both sides are held to the same document.
 * WireMock only stands in for the server; what is asserted is the request the bridge really sent.
 */
class ErpClientContractTest {

    static final OpenApiInteractionValidator CONTRACT =
            OpenApiInteractionValidator.createForSpecificationUrl("static/openapi.yaml").build();

    @RegisterExtension
    static WireMockExtension erp = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    ErpClient client;

    @BeforeEach
    void setUp() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(1)).build());
        factory.setReadTimeout(Duration.ofMillis(500));
        client = new ErpClient(RestClient.builder().baseUrl(erp.baseUrl()).requestFactory(factory).build());
    }

    /** The demo encounter all the way through extractor, rules and mapper: exactly what the worker sends. */
    static ErpInvoiceRequest demoRequest() {
        var patients = DemoDataset.patients(1, 1);
        var e = new DemoDataset.DemoEncounter("enc-0042", patients.get(0),
                Instant.parse("2026-10-03T14:30:00Z"), "J06.9",
                List.of(DemoDataset.PROCEDURES.get(0), DemoDataset.PROCEDURES.get(1)));
        Bundle bundle = DemoDataset.encounterBundle(e);
        var draft = new BillingRules().draft(new BillableEncounterExtractor().extract(
                (Encounter) bundle.getEntry().get(2).getResource(), DemoDataset.coverage(e.patient()),
                (Claim) bundle.getEntry().get(1).getResource(), List.of((Condition) bundle.getEntry().get(0).getResource()),
                Optional.empty()));
        return InvoiceRequestMapper.INSTANCE.toRequest(draft);
    }

    static final String INVOICE_201 = """
            {"invoiceId":"INV-1","encounterId":"enc-0042","customerCode":"CUST-001","issueDate":"2026-10-03",
             "currency":"BRL","total":162.50,"status":"ISSUED","createdAt":"2026-10-03T15:00:00Z"}""";

    @Test
    void sentInvoiceMatchesTheOpenApiContract() {
        erp.stubFor(post("/api/invoices").willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody(INVOICE_201)));

        client.createInvoice("7d7c6c1e-0000-4000-8000-000000000001", demoRequest());

        LoggedRequest sent = erp.getAllServeEvents().get(0).getRequest();
        ValidationReport report = CONTRACT.validateRequest(new SimpleRequest.Builder(Request.Method.POST, "/api/invoices")
                .withContentType("application/json")
                .withHeader("Idempotency-Key", sent.getHeader("Idempotency-Key"))
                .withBody(sent.getBodyAsString())
                .build());
        assertThat(report.getMessages()).as(sent.getBodyAsString()).isEmpty();
    }

    /** Golden body: changing a field name, a date format or the patient reference breaks this on purpose. */
    @Test
    void sentInvoiceHasTheExpectedShape() {
        erp.stubFor(post("/api/invoices").willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody(INVOICE_201)));

        client.createInvoice("key-1", demoRequest());

        erp.verify(postRequestedFor(urlPathEqualTo("/api/invoices"))
                .withHeader("Idempotency-Key", equalTo("key-1"))
                .withRequestBody(equalToJson("""
                        {"encounterId":"enc-0042","customerCode":"CUST-001","patientReference":"Patient/pat-01",
                         "issueDate":"2026-10-03","currency":"BRL","diagnosisCodes":["J06.9"],
                         "lines":[{"code":"CONS-01","description":"Consulta em consultório","quantity":1,"unitPrice":150.00,"amount":150.00},
                                  {"code":"LAB-HEMO","description":"Hemograma completo","quantity":1,"unitPrice":12.50,"amount":12.50}],
                         "total":162.50}""")));
        String body = erp.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(body).doesNotContain(DemoDataset.patients(1, 1).get(0).cpf())
                .doesNotContain(DemoDataset.patients(1, 1).get(0).family());
    }

    @Test
    void createdAndReplayedReceipts() {
        erp.stubFor(post("/api/invoices").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withHeader("Idempotent-Replayed", "true")
                .withBody(INVOICE_201)));

        ErpClient.InvoiceReceipt receipt = client.createInvoice("key-2", demoRequest());

        assertThat(receipt).isEqualTo(new ErpClient.InvoiceReceipt("INV-1", true));
    }

    @Test
    void fourHundredsArePermanentAndCarryOnlyTheErrorCode() {
        erp.stubFor(post("/api/invoices").willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"unknown_customer\",\"message\":\"customer CUST-404 does not exist\"}")));

        assertThatThrownBy(() -> client.createInvoice("key-3", demoRequest()))
                .isInstanceOfSatisfying(ErpException.Permanent.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.getMessage()).isEqualTo("ERP 422 unknown_customer");
                });
    }

    @Test
    void fiveHundredsTimeoutsAndBadBodiesAreTransient() {
        erp.stubFor(post("/api/invoices").willReturn(aResponse().withStatus(503).withBody("not json")));
        assertThatThrownBy(() -> client.createInvoice("key-4", demoRequest()))
                .isInstanceOf(ErpException.Transient.class).hasMessage("ERP 503 (unreadable body)");

        erp.stubFor(post("/api/invoices").willReturn(aResponse().withStatus(201).withFixedDelay(1500)
                .withHeader("Content-Type", "application/json").withBody(INVOICE_201)));
        assertThatThrownBy(() -> client.createInvoice("key-5", demoRequest()))
                .isInstanceOf(ErpException.Transient.class).hasMessageStartingWith("ERP unreachable");

        erp.stubFor(post("/api/invoices").willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("{}")));
        assertThatThrownBy(() -> client.createInvoice("key-6", demoRequest()))
                .isInstanceOf(ErpException.Transient.class).hasMessageContaining("without an invoiceId");
    }

    @Test
    void readsRegistriesAndInvoicesInTheContractFormat() {
        erp.stubFor(get(urlPathEqualTo("/api/customers")).withQueryParam("updatedSince", equalTo("2026-10-01T00:00:00Z"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        [{"code":"CUST-001","name":"Operadora","taxId":"11222333000181","active":true,
                          "updatedAt":"2026-10-02T10:00:00Z"}]""")));
        erp.stubFor(get(urlPathEqualTo("/api/locations")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        [{"code":"LOC-01","name":"Centro","city":null,"active":true,"updatedAt":"2026-10-02T10:00:00Z"}]""")));
        erp.stubFor(get(urlPathEqualTo("/api/invoices")).withQueryParam("issueDate", equalTo("2026-10-03"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("[" + INVOICE_201 + "]")));

        assertThat(client.customersUpdatedSince(Instant.parse("2026-10-01T00:00:00Z")))
                .singleElement().satisfies(c -> {
                    assertThat(c.code()).isEqualTo("CUST-001");
                    assertThat(c.updatedAt()).isEqualTo(Instant.parse("2026-10-02T10:00:00Z"));
                });
        assertThat(client.locationsUpdatedSince(null)).singleElement().satisfies(l -> assertThat(l.city()).isNull());
        assertThat(client.invoicesIssuedOn(LocalDate.of(2026, 10, 3)))
                .containsExactly(new ErpClient.ErpInvoice("INV-1", "enc-0042", "2026-10-03"));
    }
}
