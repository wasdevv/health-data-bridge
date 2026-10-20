package dev.wasdevv.hdb.sync.registry;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import dev.wasdevv.hdb.core.fhir.Systems;
import dev.wasdevv.hdb.fhir.store.ResourceStore;
import dev.wasdevv.hdb.fhir.store.SearchQuery;
import dev.wasdevv.hdb.sync.SyncIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Organization;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

class RegistrySyncIT extends SyncIntegrationTest {

    @Autowired
    RegistrySync sync;
    @Autowired
    ResourceStore store;
    @Autowired
    TestRestTemplate http;

    static String customer(String code, String name, String updatedAt) {
        return """
                {"code":"%s","name":"%s","taxId":"11222333000181","active":true,"updatedAt":"%s"}"""
                .formatted(code, name, updatedAt);
    }

    void customersSince(String since, String... customers) {
        erp.stubFor(get(urlPathEqualTo("/api/customers"))
                .withQueryParam("updatedSince", since == null ? absent() : equalTo(since))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("[" + String.join(",", customers) + "]")));
    }

    void locationsSince(String since, String body) {
        erp.stubFor(get(urlPathEqualTo("/api/locations"))
                .withQueryParam("updatedSince", since == null ? absent() : equalTo(since))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    List<Organization> organizations(String code) {
        return store.search(Organization.class, SearchQuery.of("Organization").token("identifier", Systems.ERP_CUSTOMER, code));
    }

    @Test
    void firstRunCreatesSecondRunSkipsUnchangedThirdRunUpdatesInPlace() {
        customersSince(null, customer("CUST-001", "Operadora A", "2026-10-01T10:00:00Z"),
                customer("CUST-002", "Operadora B", "2026-10-01T11:00:00Z"));
        locationsSince(null, """
                [{"code":"LOC-01","name":"Unidade Centro","city":"Curitiba","active":true,"updatedAt":"2026-10-01T09:00:00Z"}]""");

        RegistrySync.Result first = sync.run();

        assertThat(first.customers().written()).isEqualTo(2);
        assertThat(first.customers().cursor()).isEqualTo(Instant.parse("2026-10-01T11:00:00Z"));
        assertThat(first.locations().written()).isEqualTo(1);
        assertThat(sync.cursor("customers")).isEqualTo(Instant.parse("2026-10-01T11:00:00Z"));
        Location loc = store.search(Location.class, SearchQuery.of("Location").token("identifier", Systems.ERP_LOCATION, "LOC-01")).get(0);
        assertThat(loc.getAddress().getCity()).isEqualTo("Curitiba");

        // updatedSince is inclusive: the last record comes back and must not become version 2
        customersSince("2026-10-01T11:00:00Z", customer("CUST-002", "Operadora B", "2026-10-01T11:00:00Z"));
        locationsSince("2026-10-01T09:00:00Z", "[]");
        RegistrySync.Result second = sync.run();
        assertThat(second.customers().unchanged()).isEqualTo(1);
        assertThat(second.customers().written()).isZero();
        assertThat(organizations("CUST-002").get(0).getMeta().getVersionId()).isEqualTo("1");

        customersSince("2026-10-01T11:00:00Z", customer("CUST-002", "Operadora B Saúde", "2026-10-02T08:00:00Z"));
        RegistrySync.Result third = sync.run();
        assertThat(third.customers().written()).isEqualTo(1);
        assertThat(organizations("CUST-002")).singleElement().satisfies(o -> {
            assertThat(o.getName()).isEqualTo("Operadora B Saúde");
            assertThat(o.getMeta().getVersionId()).isEqualTo("2");
        });
        assertThat(sync.cursor("customers")).isEqualTo(Instant.parse("2026-10-02T08:00:00Z"));
    }

    @Test
    void ambiguousRecordIsRejectedAndDoesNotStallTheCursor() {
        Organization dup = new Organization().setName("dup");
        dup.addIdentifier().setSystem(Systems.ERP_CUSTOMER).setValue("CUST-DUP");
        store.create(dup);
        store.create(dup.copy());
        customersSince(null, customer("CUST-DUP", "Dup", "2026-10-01T10:00:00Z"),
                customer("CUST-003", "Operadora C", "2026-10-01T12:00:00Z"));
        locationsSince(null, "[]");

        RegistrySync.Result r = sync.run();

        assertThat(r.customers().rejected()).isEqualTo(1);
        assertThat(r.customers().written()).isEqualTo(1);
        assertThat(sync.cursor("customers")).isEqualTo(Instant.parse("2026-10-01T12:00:00Z"));
    }

    @Test
    void adminEndpointRunsItAndReportsErpOutage() {
        customersSince(null, customer("CUST-001", "Operadora A", "2026-10-01T10:00:00Z"));
        locationsSince(null, "[]");
        var ok = http.postForEntity("/admin/registry/sync", null, RegistrySync.Result.class);
        assertThat(ok.getBody().customers().written()).isEqualTo(1);

        erp.stubFor(get(urlPathEqualTo("/api/customers")).willReturn(aResponse().withStatus(503)));
        assertThat(http.postForEntity("/admin/registry/sync", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
