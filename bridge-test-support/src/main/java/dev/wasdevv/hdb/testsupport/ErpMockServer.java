package dev.wasdevv.hdb.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wasdevv.hdb.erpmock.ErpMockApplication;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The real erp-mock, started once per JVM on a free port next to the bridge under test, and driven
 * through its admin API: the end-to-end and chaos tests fail the same server the demo uses.
 */
public final class ErpMockServer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private static volatile String baseUrl;

    private ErpMockServer() {
    }

    public static synchronized String baseUrl() {
        if (baseUrl == null) {
            ConfigurableApplicationContext ctx = ErpMockApplication.start("--server.port=0");
            baseUrl = "http://localhost:" + ctx.getEnvironment().getProperty("local.server.port");
        }
        return baseUrl;
    }

    /** {@code faults} is the JSON of {@code PUT /admin/faults/{endpoint}}, e.g. {@code {"down":true}}. */
    public static void faults(String endpoint, String faults) {
        send("PUT", "/admin/faults/" + endpoint, faults);
    }

    public static void clearFaults() {
        send("DELETE", "/admin/faults", null);
    }

    /** Empties invoices and faults and seeds the registry again, with or without the planted divergence. */
    public static void reset(boolean plantedDivergence) {
        send("POST", "/admin/reset?plantedDivergence=" + plantedDivergence, null);
    }

    /** Creates or changes a customer in the ERP's registry, as an ERP user would. */
    public static void customer(String code, String json) {
        send("PUT", "/admin/customers/" + code, json);
    }

    public static JsonNode stats() {
        return send("GET", "/admin/stats", null);
    }

    public static JsonNode invoices(String query) {
        return send("GET", "/api/invoices" + (query == null ? "" : "?" + query), null);
    }

    private static JsonNode send(String method, String path, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException(method + " " + path + " -> " + response.statusCode() + " " + response.body());
            }
            return response.body().isEmpty() ? null : JSON.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
