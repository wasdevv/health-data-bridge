package dev.wasdevv.hdb.core.fhir;

/** Identifier and code systems shared by the FHIR side, the sync worker and the fixtures. */
public final class Systems {

    /** Brazilian CPF, as named by RNDS. Fixtures only ever carry generated CPFs. */
    public static final String CPF = "http://rnds.saude.gov.br/fhir/r4/NamingSystem/cpf";
    public static final String CNPJ = "http://rnds.saude.gov.br/fhir/r4/NamingSystem/cnpj";
    public static final String ICD_10 = "http://hl7.org/fhir/sid/icd-10";
    public static final String ERP_CUSTOMER = "https://health-data-bridge.example/erp/customer";
    public static final String ERP_LOCATION = "https://health-data-bridge.example/erp/location";
    public static final String BILLABLE_ENCOUNTER_PROFILE =
            "https://health-data-bridge.example/fhir/StructureDefinition/billable-encounter";

    private Systems() {
    }
}
