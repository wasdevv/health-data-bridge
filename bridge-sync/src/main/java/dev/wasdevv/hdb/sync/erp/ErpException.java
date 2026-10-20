package dev.wasdevv.hdb.sync.erp;

/**
 * An ERP call that did not succeed. {@link Transient} is worth retrying and counts against the
 * circuit breaker; {@link Permanent} (a 4xx) will fail the same way every time, and says nothing
 * about the ERP's health.
 */
public abstract sealed class ErpException extends RuntimeException permits ErpException.Transient, ErpException.Permanent {

    ErpException(String message, Throwable cause) {
        super(message, cause);
    }

    public static final class Transient extends ErpException {
        public Transient(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class Permanent extends ErpException {
        private final int status;

        public Permanent(int status, String message) {
            super(message, null);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
