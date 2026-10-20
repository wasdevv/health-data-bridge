package dev.wasdevv.hdb.erpmock;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Every error the mock answers has the shape {@code {"error": code, "message": text}}. */
@RestControllerAdvice
class ErrorHandlers {

    @ExceptionHandler(ErpError.class)
    ResponseEntity<Map<String, String>> erpError(ErpError e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.code(), "message", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        String fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .sorted()
                .reduce((a, b) -> a + "; " + b)
                .orElse("invalid request");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", "invalid_request", "message", fields));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<Map<String, String>> missingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "missing_idempotency_key", "message", e.getHeaderName() + " is required"));
    }
}
