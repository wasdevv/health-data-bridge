package dev.wasdevv.hdb.fhir.store;

import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** The one kind of conditional URL the bridge accepts: {@code Type?identifier=system|value}. */
public record IdentifierCondition(String system, String value) {

    public static IdentifierCondition parse(String url) {
        String query = url.substring(url.indexOf('?') + 1);
        String raw = query.startsWith("identifier=")
                ? URLDecoder.decode(query.substring("identifier=".length()), StandardCharsets.UTF_8)
                : "";
        int bar = raw.indexOf('|');
        if (query.contains("&") || bar <= 0 || bar == raw.length() - 1) {
            throw new InvalidRequestException(
                    "Conditional update supports only ?identifier=system|value, got " + url);
        }
        return new IdentifierCondition(raw.substring(0, bar), raw.substring(bar + 1));
    }
}
