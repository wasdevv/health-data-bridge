package dev.wasdevv.hdb.testsupport;

import java.util.function.Supplier;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The PostgreSQL database for integration tests.
 *
 * <ol>
 *   <li>{@code HDB_TEST_JDBC_URL} (plus {@code HDB_TEST_DB_USER}/{@code HDB_TEST_DB_PASSWORD}) points at an
 *       existing database: the path for machines without Docker.</li>
 *   <li>Otherwise a Testcontainers {@code postgres:16} container, shared by the whole JVM.</li>
 *   <li>Neither: {@link RequiresTestDatabase} reports the class as <b>skipped</b>, never as passed.</li>
 * </ol>
 *
 * Each module migrates into its own schema, so running one module's tests after another's never meets
 * a Flyway history with migrations it does not have.
 */
public final class TestDatabase {

    public static final String IMAGE = "postgres:16-alpine";

    private static volatile Connection connection;

    public record Connection(String jdbcUrl, String username, String password, String source) {
    }

    private TestDatabase() {
    }

    public static boolean available() {
        return configuredUrl() != null || dockerAvailable();
    }

    public static Connection get() {
        Connection c = connection;
        if (c == null) {
            synchronized (TestDatabase.class) {
                if (connection == null) {
                    connection = resolve();
                }
                c = connection;
            }
        }
        return c;
    }

    /** Spring properties for a datasource whose tables live in {@code schema}. */
    public static void register(PropertySink registry, String schema) {
        Connection c = get();
        String sep = c.jdbcUrl().contains("?") ? "&" : "?";
        registry.add("spring.datasource.url", () -> c.jdbcUrl() + sep + "currentSchema=" + schema);
        registry.add("spring.datasource.username", c::username);
        registry.add("spring.datasource.password", c::password);
        registry.add("spring.flyway.schemas", () -> schema);
        registry.add("spring.flyway.default-schema", () -> schema);
    }

    /** Same shape as Spring's {@code DynamicPropertyRegistry}, so this module does not need spring-test. */
    @FunctionalInterface
    public interface PropertySink {
        void add(String name, Supplier<Object> value);
    }

    private static Connection resolve() {
        String url = configuredUrl();
        if (url != null) {
            return new Connection(url, env("HDB_TEST_DB_USER", "postgres"), env("HDB_TEST_DB_PASSWORD", ""),
                    "HDB_TEST_JDBC_URL");
        }
        if (!dockerAvailable()) {
            throw new IllegalStateException("No database for integration tests: set HDB_TEST_JDBC_URL or run with Docker");
        }
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(IMAGE);
        container.start();
        return new Connection(container.getJdbcUrl(), container.getUsername(), container.getPassword(), "testcontainers");
    }

    private static String configuredUrl() {
        String url = System.getenv("HDB_TEST_JDBC_URL");
        return url == null || url.isBlank() ? null : url;
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null ? fallback : v;
    }
}
