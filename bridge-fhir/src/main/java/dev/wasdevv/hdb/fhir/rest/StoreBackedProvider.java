package dev.wasdevv.hdb.fhir.rest;

import ca.uhn.fhir.rest.annotation.ConditionalUrlParam;
import ca.uhn.fhir.rest.annotation.Create;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import ca.uhn.fhir.rest.annotation.Update;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.DateParam;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.param.ParamPrefixEnum;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.SimpleBundleProvider;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import dev.wasdevv.hdb.fhir.store.IdentifierCondition;
import dev.wasdevv.hdb.fhir.store.ResourceStore;
import dev.wasdevv.hdb.fhir.store.SearchQuery;
import java.time.LocalDate;
import java.util.List;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Resource;

/**
 * Read, create and update for one resource type, all through {@link ResourceStore}. Subclasses only
 * declare their search parameters. Conditional update supports {@code identifier=system|value}.
 */
public abstract class StoreBackedProvider<T extends Resource> implements IResourceProvider {

    protected final ResourceStore store;
    private final Class<T> type;

    protected StoreBackedProvider(ResourceStore store, Class<T> type) {
        this.store = store;
        this.type = type;
    }

    @Override
    public Class<T> getResourceType() {
        return type;
    }

    @Read
    public T read(@IdParam IdType id) {
        return store.read(type, id.getIdPart());
    }

    @Create
    public MethodOutcome create(@ResourceParam IBaseResource resource) {
        T saved = store.create(type.cast(resource));
        return new MethodOutcome(saved.getIdElement(), true).setResource(saved);
    }

    @Update
    public MethodOutcome update(@IdParam IdType id, @ResourceParam IBaseResource resource,
                                @ConditionalUrlParam String conditionalUrl) {
        T incoming = type.cast(resource);
        T saved;
        if (conditionalUrl != null) {
            IdentifierCondition condition = IdentifierCondition.parse(conditionalUrl);
            saved = store.upsertByIdentifier(incoming, condition.system(), condition.value());
        } else {
            if (incoming.hasIdElement() && !id.getIdPart().equals(incoming.getIdElement().getIdPart())) {
                throw new InvalidRequestException("Resource id " + incoming.getIdElement().getIdPart()
                        + " does not match the URL id " + id.getIdPart());
            }
            saved = store.update(incoming, id.getIdPart(), id.getVersionIdPart());
        }
        boolean created = "1".equals(saved.getMeta().getVersionId());
        return new MethodOutcome(saved.getIdElement(), created).setResource(saved);
    }

    protected IBundleProvider search(SearchQuery query) {
        List<T> found = store.search(type, query);
        return new SimpleBundleProvider(found);
    }

    protected SearchQuery query() {
        return SearchQuery.of(store.resourceType(type));
    }

    protected static void token(SearchQuery q, String param, TokenParam token) {
        if (token != null) {
            q.token(param, token.getSystem() == null || token.getSystem().isEmpty() ? null : token.getSystem(),
                    token.getValue());
        }
    }

    protected static void reference(SearchQuery q, String param, ReferenceParam ref, String defaultType) {
        if (ref != null) {
            String refType = ref.getResourceType() == null ? defaultType : ref.getResourceType();
            q.exact(param, refType + "/" + ref.getIdPart());
        }
    }

    /**
     * Day-precision bounds read from the parameter text, not from {@code getLowerBoundAsInstant()}, which
     * resolves a bare date in the JVM's time zone. The index holds UTC days, so the query must too.
     */
    protected static void date(SearchQuery q, String param, DateRangeParam range) {
        if (range != null) {
            q.dateRange(param, day(range.getLowerBound(), 1), day(range.getUpperBound(), -1));
        }
    }

    private static LocalDate day(DateParam bound, int exclusiveShift) {
        if (bound == null || bound.getValueAsString() == null) {
            return null;
        }
        LocalDate day = LocalDate.parse(bound.getValueAsString().substring(0, 10));
        ParamPrefixEnum prefix = bound.getPrefix();
        boolean exclusive = prefix == ParamPrefixEnum.GREATERTHAN || prefix == ParamPrefixEnum.STARTS_AFTER
                || prefix == ParamPrefixEnum.LESSTHAN || prefix == ParamPrefixEnum.ENDS_BEFORE;
        return exclusive ? day.plusDays(exclusiveShift) : day;
    }
}
