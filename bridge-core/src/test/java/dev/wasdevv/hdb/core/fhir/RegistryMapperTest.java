package dev.wasdevv.hdb.core.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import dev.wasdevv.hdb.core.erp.ErpCustomer;
import dev.wasdevv.hdb.core.erp.ErpLocation;
import java.time.Instant;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Organization;
import org.junit.jupiter.api.Test;

class RegistryMapperTest {

    private final RegistryMapper mapper = new RegistryMapper();

    @Test
    void customerBecomesInsurerOrganizationKeyedByErpCode() {
        Organization org = mapper.toOrganization(
                new ErpCustomer("CUST-001", "Operadora Exemplo", "11222333000181", true, Instant.EPOCH));

        assertThat(org.getIdentifier()).extracting(i -> i.getSystem() + "|" + i.getValue())
                .containsExactly(Systems.ERP_CUSTOMER + "|CUST-001", Systems.CNPJ + "|11222333000181");
        assertThat(org.getName()).isEqualTo("Operadora Exemplo");
        assertThat(org.getActive()).isTrue();
        assertThat(org.getTypeFirstRep().getCodingFirstRep().getCode()).isEqualTo("ins");
    }

    @Test
    void customerWithoutTaxIdHasOnlyTheErpIdentifier() {
        Organization org = mapper.toOrganization(new ErpCustomer("CUST-9", "X", null, false, Instant.EPOCH));
        assertThat(org.getIdentifier()).hasSize(1);
        assertThat(org.getActive()).isFalse();
    }

    @Test
    void locationKeepsStatusAndCity() {
        Location loc = mapper.toLocation(new ErpLocation("LOC-01", "Unidade Centro", "Curitiba", false, Instant.EPOCH));

        assertThat(loc.getIdentifierFirstRep().getSystem()).isEqualTo(Systems.ERP_LOCATION);
        assertThat(loc.getIdentifierFirstRep().getValue()).isEqualTo("LOC-01");
        assertThat(loc.getStatus()).isEqualTo(Location.LocationStatus.INACTIVE);
        assertThat(loc.getAddress().getCity()).isEqualTo("Curitiba");

        assertThat(mapper.toLocation(new ErpLocation("LOC-02", "Sem cidade", null, true, Instant.EPOCH)).hasAddress())
                .isFalse();
    }
}
