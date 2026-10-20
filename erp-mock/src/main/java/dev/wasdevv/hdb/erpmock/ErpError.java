package dev.wasdevv.hdb.erpmock;

import org.springframework.http.HttpStatus;

/** Any non-2xx answer of the mock, rendered as {@code {"error": code, "message": ...}}. */
public class ErpError extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ErpError(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
