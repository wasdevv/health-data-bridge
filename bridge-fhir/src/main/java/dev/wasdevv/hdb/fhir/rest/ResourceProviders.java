package dev.wasdevv.hdb.fhir.rest;

import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import dev.wasdevv.hdb.fhir.store.ResourceStore;
import dev.wasdevv.hdb.fhir.store.SearchQuery;
import java.util.List;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;

/** The eight resource types the bridge serves, each with the search parameters it indexes. */
public final class ResourceProviders {

    private ResourceProviders() {
    }

    public static List<StoreBackedProvider<?>> all(ResourceStore store) {
        return List.of(new PatientProvider(store), new EncounterProvider(store), new CoverageProvider(store),
                new ClaimProvider(store), new ConditionProvider(store), new OrganizationProvider(store),
                new LocationProvider(store), new PractitionerProvider(store));
    }

    public static class PatientProvider extends StoreBackedProvider<Patient> {
        PatientProvider(ResourceStore store) {
            super(store, Patient.class);
        }

        @Search
        public IBundleProvider search(@OptionalParam(name = Patient.SP_IDENTIFIER) TokenParam identifier) {
            SearchQuery q = query();
            token(q, "identifier", identifier);
            return search(q);
        }
    }

    public static class EncounterProvider extends StoreBackedProvider<Encounter> {
        EncounterProvider(ResourceStore store) {
            super(store, Encounter.class);
        }

        @Search
        public IBundleProvider search(@OptionalParam(name = Encounter.SP_PATIENT) ReferenceParam patient,
                                      @OptionalParam(name = Encounter.SP_DATE) DateRangeParam date,
                                      @OptionalParam(name = Encounter.SP_STATUS) TokenParam status,
                                      @OptionalParam(name = Encounter.SP_IDENTIFIER) TokenParam identifier) {
            SearchQuery q = query();
            reference(q, "patient", patient, "Patient");
            date(q, "date", date);
            token(q, "status", status);
            token(q, "identifier", identifier);
            return search(q);
        }
    }

    public static class CoverageProvider extends StoreBackedProvider<Coverage> {
        CoverageProvider(ResourceStore store) {
            super(store, Coverage.class);
        }

        @Search
        public IBundleProvider search(@OptionalParam(name = Coverage.SP_PATIENT) ReferenceParam patient,
                                      @OptionalParam(name = Coverage.SP_IDENTIFIER) TokenParam identifier) {
            SearchQuery q = query();
            reference(q, "patient", patient, "Patient");
            token(q, "identifier", identifier);
            return search(q);
        }
    }

    public static class ClaimProvider extends StoreBackedProvider<Claim> {
        ClaimProvider(ResourceStore store) {
            super(store, Claim.class);
        }

        @Search
        public IBundleProvider search(@OptionalParam(name = Claim.SP_STATUS) TokenParam status,
                                      @OptionalParam(name = Claim.SP_PATIENT) ReferenceParam patient,
                                      @OptionalParam(name = Claim.SP_ENCOUNTER) ReferenceParam encounter,
                                      @OptionalParam(name = Claim.SP_IDENTIFIER) TokenParam identifier) {
            SearchQuery q = query();
            token(q, "status", status);
            reference(q, "patient", patient, "Patient");
            reference(q, "encounter", encounter, "Encounter");
            token(q, "identifier", identifier);
            return search(q);
        }
    }

    public static class ConditionProvider extends StoreBackedProvider<Condition> {
        ConditionProvider(ResourceStore store) {
            super(store, Condition.class);
        }

        @Search
        public IBundleProvider search(@OptionalParam(name = Condition.SP_PATIENT) ReferenceParam patient,
                                      @OptionalParam(name = Condition.SP_ENCOUNTER) ReferenceParam encounter) {
            SearchQuery q = query();
            reference(q, "patient", patient, "Patient");
            reference(q, "encounter", encounter, "Encounter");
            return search(q);
        }
    }

    public static class OrganizationProvider extends StoreBackedProvider<Organization> {
        OrganizationProvider(ResourceStore store) {
            super(store, Organization.class);
        }

        @Search
        public IBundleProvider search(@OptionalParam(name = Organization.SP_IDENTIFIER) TokenParam identifier) {
            SearchQuery q = query();
            token(q, "identifier", identifier);
            return search(q);
        }
    }

    public static class LocationProvider extends StoreBackedProvider<Location> {
        LocationProvider(ResourceStore store) {
            super(store, Location.class);
        }

        @Search
        public IBundleProvider search(@OptionalParam(name = Location.SP_IDENTIFIER) TokenParam identifier) {
            SearchQuery q = query();
            token(q, "identifier", identifier);
            return search(q);
        }
    }

    public static class PractitionerProvider extends StoreBackedProvider<Practitioner> {
        PractitionerProvider(ResourceStore store) {
            super(store, Practitioner.class);
        }

        @Search
        public IBundleProvider search(@OptionalParam(name = Practitioner.SP_IDENTIFIER) TokenParam identifier) {
            SearchQuery q = query();
            token(q, "identifier", identifier);
            return search(q);
        }
    }
}
