package dev.wasdevv.hdb.erpmock;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The mock's behavior over HTTP, with every {@code /api} exchange checked against {@code static/openapi.yaml}:
 * the contract the bridge's tests rely on is the one this server actually speaks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.name=erp-mock")
class ErpMockApiTest {

    static final OpenApiInteractionValidator CONTRACT =
            OpenApiInteractionValidator.createForSpecificationUrl("static/openapi.yaml").build();

    @LocalServerPort
    int port;
    @Autowired
    ObjectMapper json;

    final HttpClient http = HttpClient.newHttpClient();

    record Reply(int status, JsonNode body, HttpResponse<String> raw) {
    }

    @BeforeEach
    void reset() throws Exception {
        call("POST", "/admin/reset?plantedDivergence=true", null, null);
    }

    @AfterEach
    void clearFaults() throws Exception {
        call("DELETE", "/admin/faults", null, null);
    }

    static String invoice(String encounterId, String customer, String total) {
        return """
                {"encounterId":"%s","customerCode":"%s","patientReference":"Patient/pat-01","issueDate":"2026-10-03",
                 "currency":"BRL","diagnosisCodes":["J06.9"],
                 "lines":[{"code":"CONS-01","description":"Consulta","quantity":1,"unitPrice":150.00,"amount":150.00},
                          {"code":"LAB-HEMO","description":"Hemograma","quantity":2,"unitPrice":12.335,"amount":24.67}],
                 "total":%s}""".formatted(encounterId, customer, total);
    }

    Reply call(String method, String path, String key, String body) throws Exception {
        return call(method, path, key, body, true);
    }

    /** For requests that break the contract on purpose: only the response is checked against it. */
    Reply callOffContract(String method, String path, String key, String body) throws Exception {
        return call(method, path, key, body, false);
    }

    Reply call(String method, String path, String key, String body, boolean validRequest) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (path.startsWith("/api")) {
            assertContract(method, path, key, body, r, validRequest);
        }
        return new Reply(r.statusCode(), r.body().isEmpty() ? null : json.readTree(r.body()), r);
    }

    static void assertContract(String method, String path, String key, String body, HttpResponse<String> r,
                               boolean validRequest) {
        URI uri = URI.create(path);
        SimpleRequest.Builder req = new SimpleRequest.Builder(Request.Method.valueOf(method), uri.getPath())
                .withContentType("application/json");
        if (uri.getQuery() != null) {
            for (String pair : uri.getQuery().split("&")) {
                String[] kv = pair.split("=", 2);
                req.withQueryParam(kv[0], kv[1]);
            }
        }
        if (key != null) {
            req.withHeader("Idempotency-Key", key);
        }
        if (body != null) {
            req.withBody(body);
        }
        SimpleResponse.Builder res = SimpleResponse.Builder.status(r.statusCode()).withBody(r.body());
        r.headers().map().forEach((name, values) -> res.withHeader(name, values));
        ValidationReport report = validRequest
                ? CONTRACT.validate(req.build(), res.build())
                : CONTRACT.validateResponse(uri.getPath(), Request.Method.valueOf(method), res.build());
        assertThat(report.getMessages()).as("%s %s -> %d %s", method, path, r.statusCode(), r.body()).isEmpty();
    }

    @Test
    void createsInvoiceAndReplaysItForTheSameKey() throws Exception {
        Reply first = call("POST", "/api/invoices", "k-1", invoice("enc-1", "CUST-001", "174.67"));
        Reply again = call("POST", "/api/invoices", "k-1", invoice("enc-1", "CUST-001", "174.67"));

        assertThat(first.status()).isEqualTo(201);
        assertThat(first.raw().headers().firstValue("Idempotent-Replayed")).hasValue("false");
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.raw().headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(again.body().get("invoiceId")).isEqualTo(first.body().get("invoiceId"));
        assertThat(call("GET", "/api/invoices?encounterId=enc-1", null, null).body()).hasSize(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() throws Exception {
        call("POST", "/api/invoices", "k-2", invoice("enc-2", "CUST-001", "174.67"));
        Reply other = call("POST", "/api/invoices", "k-2", invoice("enc-3", "CUST-001", "174.67"));

        assertThat(other.status()).isEqualTo(422);
        assertThat(other.body().get("error").asText()).isEqualTo("idempotency_key_reused");
    }

    @Test
    void concurrentRetriesWithOneKeyIssueOneInvoice() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            calls.add(() -> call("POST", "/api/invoices", "k-race", invoice("enc-race", "CUST-002", "174.67")).status());
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(calls)) {
            statuses.add(f.get());
        }
        pool.shutdown();

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 200).hasSize(15);
        assertThat(call("GET", "/admin/stats", null, null).body().get("duplicatedEncounters")).isEmpty();
    }

    @Test
    void missingIdempotencyKeyIsBadRequest() throws Exception {
        Reply r = callOffContract("POST", "/api/invoices", null, invoice("enc-4", "CUST-001", "174.67"));
        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    void permanentRejections() throws Exception {
        assertThat(call("POST", "/api/invoices", "k-5", invoice("enc-5", "CUST-404", "174.67")).body()
                .get("error").asText()).isEqualTo("unknown_customer");
        assertThat(call("POST", "/api/invoices", "k-6", invoice("enc-6", "CUST-099", "174.67")).body()
                .get("error").asText()).isEqualTo("inactive_customer");
        Reply mismatch = call("POST", "/api/invoices", "k-7", invoice("enc-7", "CUST-001", "174.66"));
        assertThat(mismatch.status()).isEqualTo(422);
        assertThat(mismatch.body().get("error").asText()).isEqualTo("total_mismatch");
        String badLine = invoice("enc-8", "CUST-001", "174.67").replace("\"amount\":24.67", "\"amount\":24.66");
        assertThat(call("POST", "/api/invoices", "k-8", badLine).body().get("error").asText())
                .isEqualTo("line_amount_mismatch");
        String usd = invoice("enc-8b", "CUST-001", "174.67").replace("BRL", "USD");
        assertThat(callOffContract("POST", "/api/invoices", "k-8b", usd).body().get("error").asText())
                .isEqualTo("unsupported_currency");
    }

    @Test
    void structurallyInvalidRequestIs422() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/invoices"))
                .header("Content-Type", "application/json").header("Idempotency-Key", "k-9")
                .POST(HttpRequest.BodyPublishers.ofString("{\"encounterId\":\"\",\"lines\":[]}")).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(422);
        assertThat(json.readTree(r.body()).get("error").asText()).isEqualTo("invalid_request");
    }

    @Test
    void downModeAnswers503UntilCleared() throws Exception {
        call("PUT", "/admin/faults/invoices", null, "{\"down\":true}");
        assertThat(call("POST", "/api/invoices", "k-10", invoice("enc-10", "CUST-001", "174.67")).status()).isEqualTo(503);
        assertThat(call("GET", "/api/customers", null, null).status()).isEqualTo(200);

        call("DELETE", "/admin/faults", null, null);
        assertThat(call("POST", "/api/invoices", "k-10", invoice("enc-10", "CUST-001", "174.67")).status()).isEqualTo(201);
    }

    @Test
    void transientAndPermanentErrorRates() throws Exception {
        call("PUT", "/admin/faults/invoices", null, "{\"errorRate\":1.0}");
        assertThat(call("POST", "/api/invoices", "k-11", invoice("enc-11", "CUST-001", "174.67")).status()).isEqualTo(503);
        call("PUT", "/admin/faults/invoices", null, "{\"permanentErrorRate\":1.0}");
        Reply rejected = call("POST", "/api/invoices", "k-11", invoice("enc-11", "CUST-001", "174.67"));
        assertThat(rejected.status()).isEqualTo(422);
        assertThat(rejected.body().get("error").asText()).isEqualTo("rejected");
        assertThat(call("GET", "/api/invoices?encounterId=enc-11", null, null).body()).isEmpty();
    }

    @Test
    void lostResponseAfterCommitIsRecoveredByRetryingTheSameKey() throws Exception {
        call("PUT", "/admin/faults/invoices", null, "{\"failAfterCommitRate\":1.0}");
        assertThat(call("POST", "/api/invoices", "k-12", invoice("enc-12", "CUST-001", "174.67")).status()).isEqualTo(503);
        call("DELETE", "/admin/faults", null, null);

        Reply retry = call("POST", "/api/invoices", "k-12", invoice("enc-12", "CUST-001", "174.67"));

        assertThat(retry.status()).as("the invoice existed already").isEqualTo(200);
        assertThat(call("GET", "/api/invoices?encounterId=enc-12", null, null).body()).hasSize(1);
    }

    @Test
    void latencyIsInjected() throws Exception {
        call("PUT", "/admin/faults/locations", null, "{\"latencyMs\":300}");
        long t0 = System.nanoTime();
        call("GET", "/api/locations", null, null);
        assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThanOrEqualTo(300);
        assertThat(call("PUT", "/admin/faults/nope", null, "{}").status()).isEqualTo(404);
    }

    @Test
    void registriesFilterByUpdatedSinceInclusively() throws Exception {
        Reply all = call("GET", "/api/customers", null, null);
        assertThat(all.body()).hasSize(5);
        Instant last = Instant.parse(all.body().get(4).get("updatedAt").asText());

        assertThat(call("GET", "/api/customers?updatedSince=" + last, null, null).body()).hasSizeGreaterThanOrEqualTo(1);
        call("PUT", "/admin/customers/CUST-002", null, "{\"name\":\"Cooperativa Vale Verde Nova\",\"active\":true}");
        Reply changed = call("GET", "/api/customers?updatedSince=" + last.plusMillis(1), null, null);
        assertThat(changed.body()).extracting(n -> n.get("code").asText()).containsExactly("CUST-002");

        call("PUT", "/admin/locations/LOC-04", null, "{\"name\":\"Unidade Oeste\",\"city\":\"Cascavel\",\"active\":true}");
        assertThat(call("GET", "/api/locations", null, null).body()).hasSize(4);
    }

    @Test
    void plantedDivergenceIsAnInvoiceForAnEncounterThatDoesNotExist() throws Exception {
        Reply r = call("GET", "/api/invoices?issueDate=" + Seed.PLANTED_DATE, null, null);
        assertThat(r.body()).extracting(n -> n.get("encounterId").asText()).containsExactly(Seed.PLANTED_ENCOUNTER);

        call("POST", "/admin/reset", null, null);
        assertThat(call("GET", "/api/invoices?issueDate=" + Seed.PLANTED_DATE, null, null).body()).isEmpty();
    }

    @Test
    void statsCountDuplicatesAndFaults() throws Exception {
        call("PUT", "/admin/faults/invoices", null, "{\"down\":true}");
        call("POST", "/api/invoices", "k-13", invoice("enc-13", "CUST-001", "174.67"));
        JsonNode stats = call("GET", "/admin/stats", null, null).body();
        assertThat(stats.get("faultsInjected").asLong()).isPositive();
        assertThat(call("GET", "/admin/faults", null, null).body().get("invoices").get("down").asBoolean()).isTrue();
    }
}
