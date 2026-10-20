package dev.wasdevv.hdb.fhir.store;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.transaction.annotation.Transactional;

/**
 * A {@code transaction} Bundle in one database transaction: if any entry fails, nothing is written,
 * outbox rows included. Supports POST (with {@code urn:uuid:} references between entries), PUT by id
 * and conditional PUT by identifier.
 */
public class TransactionProcessor {

    private final ResourceStore store;
    private final FhirContext ctx;

    public TransactionProcessor(ResourceStore store, FhirContext ctx) {
        this.store = store;
        this.ctx = ctx;
    }

    private record Planned(Bundle.BundleEntryComponent entry, Resource resource, String id, boolean created) {
    }

    @Transactional
    public Bundle process(Bundle bundle) {
        if (bundle.getType() != Bundle.BundleType.TRANSACTION) {
            throw new InvalidRequestException("Only Bundle.type = transaction is supported, got " + bundle.getType());
        }

        // pass 1: decide every target id, so entries can reference each other before any is written
        Map<String, String> fullUrlToReference = new HashMap<>();
        List<Planned> plan = new ArrayList<>();
        for (int i = 0; i < bundle.getEntry().size(); i++) {
            Bundle.BundleEntryComponent entry = bundle.getEntry().get(i);
            Resource resource = entry.getResource();
            if (resource == null || !entry.hasRequest()) {
                throw entryError(i, new InvalidRequestException("entry needs a resource and a request"));
            }
            String id;
            boolean created;
            String url = entry.getRequest().getUrl();
            try {
                switch (entry.getRequest().getMethod()) {
                    case POST -> {
                        id = UUID.randomUUID().toString();
                        created = true;
                    }
                    case PUT -> {
                        if (url.contains("?")) {
                            IdentifierCondition condition = IdentifierCondition.parse(url);
                            var existing = store.idForIdentifier(resource.fhirType(), condition.system(), condition.value());
                            id = existing.orElseGet(() -> UUID.randomUUID().toString());
                            created = existing.isEmpty();
                        } else {
                            IdType target = new IdType(url);
                            if (!resource.fhirType().equals(target.getResourceType())) {
                                throw new InvalidRequestException("PUT " + url + " carries a " + resource.fhirType());
                            }
                            id = target.getIdPart();
                            created = store.find(resource.getClass(), id).isEmpty();
                        }
                    }
                    default -> throw new InvalidRequestException(
                            "method " + entry.getRequest().getMethod() + " is not supported in a transaction");
                }
            } catch (BaseServerResponseException e) {
                throw entryError(i, e);
            }
            if (entry.hasFullUrl()) {
                fullUrlToReference.put(entry.getFullUrl(), resource.fhirType() + "/" + id);
            }
            plan.add(new Planned(entry, resource, id, created));
        }

        // pass 2: rewrite urn:uuid references, then write in bundle order
        Bundle response = new Bundle().setType(Bundle.BundleType.TRANSACTIONRESPONSE);
        for (int i = 0; i < plan.size(); i++) {
            Planned p = plan.get(i);
            for (Reference ref : ctx.newTerser().getAllPopulatedChildElementsOfType(p.resource(), Reference.class)) {
                String target = fullUrlToReference.get(ref.getReference());
                if (target != null) {
                    ref.setReference(target);
                }
            }
            Resource saved;
            try {
                saved = store.update(p.resource(), p.id(), null);
            } catch (BaseServerResponseException e) {
                throw entryError(i, e);
            }
            response.addEntry().getResponse()
                    .setStatus(p.created() ? "201 Created" : "200 OK")
                    .setLocation(saved.fhirType() + "/" + p.id() + "/_history/" + saved.getMeta().getVersionId())
                    .setEtag("W/\"" + saved.getMeta().getVersionId() + "\"");
        }
        return response;
    }

    /** Points the client at the failing entry; the original issues (validation messages) stay in the outcome. */
    private BaseServerResponseException entryError(int index, BaseServerResponseException e) {
        OperationOutcome oo = e.getOperationOutcome() instanceof OperationOutcome existing ? existing : new OperationOutcome();
        oo.getIssue().addFirst(new OperationOutcome.OperationOutcomeIssueComponent()
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setCode(OperationOutcome.IssueType.PROCESSING)
                .setDiagnostics("Bundle.entry[" + index + "] failed: " + e.getMessage() + ". The transaction was rolled back; nothing was written.")
                .addExpression("Bundle.entry[" + index + "]"));
        e.setOperationOutcome(oo);
        return e;
    }
}
