package dev.wasdevv.hdb.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** FHIR R4 server ({@code /fhir}), outbox worker, registry sync and reconciliation in one process. */
@SpringBootApplication(scanBasePackages = {"dev.wasdevv.hdb.app", "dev.wasdevv.hdb.fhir", "dev.wasdevv.hdb.sync"})
public class BridgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(BridgeApplication.class, args);
    }
}
