package dev.wasdevv.hdb.erpmock;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * A stand-in ERP with the failure modes a real one has: slow, intermittently down, permanently
 * rejecting bad data, or committing an invoice and then losing the response.
 */
@SpringBootApplication
public class ErpMockApplication {

    public static void main(String[] args) {
        start(args);
    }

    /**
     * Reads {@code erp-mock.properties} instead of {@code application.properties}, so the mock can run
     * inside the bridge's test JVM without picking up the bridge's configuration.
     */
    public static ConfigurableApplicationContext start(String... args) {
        return new SpringApplicationBuilder(ErpMockApplication.class)
                .properties("spring.config.name=erp-mock")
                .run(args);
    }
}
