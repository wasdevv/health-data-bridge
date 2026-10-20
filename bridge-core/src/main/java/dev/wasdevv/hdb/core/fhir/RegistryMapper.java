package dev.wasdevv.hdb.core.fhir;

import dev.wasdevv.hdb.core.erp.ErpCustomer;
import dev.wasdevv.hdb.core.erp.ErpLocation;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Organization;

/**
 * ERP registry records to FHIR. The ERP code becomes the business identifier, which is what the
 * reverse sync upserts on: the same customer always lands on the same Organization.
 */
public final class RegistryMapper {

    public Organization toOrganization(ErpCustomer customer) {
        Organization org = new Organization();
        org.addIdentifier().setSystem(Systems.ERP_CUSTOMER).setValue(customer.code());
        if (customer.taxId() != null) {
            org.addIdentifier().setSystem(Systems.CNPJ).setValue(customer.taxId());
        }
        org.setActive(customer.active());
        org.setName(customer.name());
        org.addType().addCoding()
                .setSystem("http://terminology.hl7.org/CodeSystem/organization-type")
                .setCode("ins")
                .setDisplay("Insurance Company");
        return org;
    }

    public Location toLocation(ErpLocation location) {
        Location loc = new Location();
        loc.addIdentifier().setSystem(Systems.ERP_LOCATION).setValue(location.code());
        loc.setStatus(location.active() ? Location.LocationStatus.ACTIVE : Location.LocationStatus.INACTIVE);
        loc.setName(location.name());
        if (location.city() != null) {
            loc.getAddress().setCity(location.city()).setCountry("BR");
        }
        return loc;
    }
}
