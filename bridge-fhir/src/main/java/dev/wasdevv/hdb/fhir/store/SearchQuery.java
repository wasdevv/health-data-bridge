package dev.wasdevv.hdb.fhir.store;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** AND of criteria over {@code fhir_search_index}. Built by the REST providers and the sync jobs. */
public final class SearchQuery {

    sealed interface Criterion permits Token, Exact, DateRange {
    }

    /** {@code system} null matches any system, like {@code identifier=value} in FHIR. */
    record Token(String param, String system, String value) implements Criterion {
    }

    record Exact(String param, String value) implements Criterion {
    }

    /** Both ends inclusive; either may be null. */
    record DateRange(String param, LocalDate from, LocalDate to) implements Criterion {
    }

    private final String resourceType;
    private final List<Criterion> criteria = new ArrayList<>();

    private SearchQuery(String resourceType) {
        this.resourceType = resourceType;
    }

    public static SearchQuery of(String resourceType) {
        return new SearchQuery(resourceType);
    }

    public SearchQuery token(String param, String system, String value) {
        criteria.add(new Token(param, system, value));
        return this;
    }

    public SearchQuery exact(String param, String value) {
        criteria.add(new Exact(param, value));
        return this;
    }

    public SearchQuery dateRange(String param, LocalDate from, LocalDate to) {
        criteria.add(new DateRange(param, from, to));
        return this;
    }

    String resourceType() {
        return resourceType;
    }

    List<Criterion> criteria() {
        return criteria;
    }
}
