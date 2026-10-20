package dev.wasdevv.hdb.sync.erp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wasdevv.hdb.core.erp.ErpCustomer;
import dev.wasdevv.hdb.core.erp.ErpInvoiceRequest;
import dev.wasdevv.hdb.core.erp.ErpLocation;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

/**
 * The ERP's HTTP API ({@code erp-mock/src/main/resources/static/openapi.yaml}). Every failure comes out
 * as an {@link ErpException}: 4xx is permanent, 5xx and I/O (including timeouts) are transient. Error
 * bodies are reduced to their {@code error} code, so nothing the ERP echoes back reaches a log line.
 */
public class ErpClient {

    public record InvoiceReceipt(String invoiceId, boolean replayed) {
    }

    public record ErpInvoice(String invoiceId, String encounterId, String issueDate) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;

    public ErpClient(RestClient http) {
        this.http = http;
    }

    public InvoiceReceipt createInvoice(String idempotencyKey, ErpInvoiceRequest request) {
        return call(() -> {
            var response = http.post().uri("/api/invoices")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toEntity(JsonNode.class);
            JsonNode body = response.getBody();
            if (body == null || !body.hasNonNull("invoiceId")) {
                throw new ErpException.Transient("ERP answered " + response.getStatusCode() + " without an invoiceId", null);
            }
            return new InvoiceReceipt(body.get("invoiceId").asText(), response.getStatusCode().value() == 200);
        });
    }

    public List<ErpInvoice> invoicesIssuedOn(LocalDate day) {
        return call(() -> http.get().uri(b -> b.path("/api/invoices").queryParam("issueDate", day).build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<ErpInvoice>>() {}));
    }

    public List<ErpCustomer> customersUpdatedSince(Instant since) {
        return call(() -> http.get().uri(b -> updatedSince(b.path("/api/customers"), since))
                .retrieve()
                .body(new ParameterizedTypeReference<List<ErpCustomer>>() {}));
    }

    public List<ErpLocation> locationsUpdatedSince(Instant since) {
        return call(() -> http.get().uri(b -> updatedSince(b.path("/api/locations"), since))
                .retrieve()
                .body(new ParameterizedTypeReference<List<ErpLocation>>() {}));
    }

    private static URI updatedSince(UriBuilder b, Instant since) {
        return since == null ? b.build() : b.queryParam("updatedSince", since).build();
    }

    private interface Call<T> {
        T run();
    }

    private static <T> T call(Call<T> call) {
        try {
            return call.run();
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            String detail = "ERP " + status.value() + " " + errorCode(e);
            if (status.is4xxClientError()) {
                throw new ErpException.Permanent(status.value(), detail);
            }
            throw new ErpException.Transient(detail, e);
        } catch (ResourceAccessException e) {
            throw new ErpException.Transient("ERP unreachable: " + e.getMostSpecificCause().getClass().getSimpleName(), e);
        }
    }

    private static String errorCode(RestClientResponseException e) {
        try {
            // parsed by hand: an error page may come without a JSON content type
            JsonNode body = JSON.readTree(e.getResponseBodyAsString());
            return body != null && body.hasNonNull("error") ? body.get("error").asText() : "(no error code)";
        } catch (IOException | RuntimeException parse) {
            return "(unreadable body)";
        }
    }
}
