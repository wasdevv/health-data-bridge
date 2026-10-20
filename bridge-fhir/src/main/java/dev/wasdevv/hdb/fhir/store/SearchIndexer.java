package dev.wasdevv.hdb.fhir.store;

import dev.wasdevv.hdb.core.fhir.BillableEncounterExtractor;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;

/**
 * The search values of a resource, one entry per row of {@code fhir_search_index}. Only the
 * parameters the CapabilityStatement advertises are indexed: no names, so a search index dump
 * carries no patient names either.
 */
final class SearchIndexer {

    record Entry(String param, String system, String value, LocalDate date) {
    }

    List<Entry> index(Resource resource) {
        List<Entry> out = new ArrayList<>();
        switch (resource) {
            case Patient p -> identifiers(out, p.getIdentifier());
            case Encounter e -> {
                identifiers(out, e.getIdentifier());
                reference(out, "patient", e.getSubject());
                if (e.hasStatus()) {
                    out.add(new Entry("status", null, e.getStatus().toCode(), null));
                }
                if (e.getPeriod().hasStart()) {
                    // same day billing uses as the invoice date, or reconciliation would compare different days
                    out.add(new Entry("date", null, null,
                            BillableEncounterExtractor.toUtcDate(e.getPeriod().getStart())));
                }
            }
            case Claim c -> {
                identifiers(out, c.getIdentifier());
                reference(out, "patient", c.getPatient());
                if (c.hasStatus()) {
                    out.add(new Entry("status", null, c.getStatus().toCode(), null));
                }
                c.getItem().forEach(item -> item.getEncounter().forEach(ref -> reference(out, "encounter", ref)));
            }
            case Coverage c -> {
                identifiers(out, c.getIdentifier());
                reference(out, "patient", c.getBeneficiary());
            }
            case Condition c -> {
                identifiers(out, c.getIdentifier());
                reference(out, "patient", c.getSubject());
                reference(out, "encounter", c.getEncounter());
            }
            case Organization o -> identifiers(out, o.getIdentifier());
            case Location l -> identifiers(out, l.getIdentifier());
            case Practitioner p -> identifiers(out, p.getIdentifier());
            default -> {
            }
        }
        return out;
    }

    private static void identifiers(List<Entry> out, List<Identifier> identifiers) {
        for (Identifier id : identifiers) {
            if (id.hasValue()) {
                out.add(new Entry("identifier", id.getSystem(), id.getValue(), null));
            }
        }
    }

    private static void reference(List<Entry> out, String param, Reference ref) {
        if (ref.hasReference()) {
            var el = ref.getReferenceElement();
            out.add(new Entry(param, null, el.getResourceType() + "/" + el.getIdPart(), null));
        }
    }
}
