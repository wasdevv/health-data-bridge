package dev.wasdevv.hdb.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.server.FifoMemoryPagingProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import dev.wasdevv.hdb.fhir.rest.ResourceProviders;
import dev.wasdevv.hdb.fhir.rest.TransactionProvider;
import dev.wasdevv.hdb.fhir.store.ResourceStore;
import dev.wasdevv.hdb.fhir.store.ResourceWriteListener;
import dev.wasdevv.hdb.fhir.store.TransactionProcessor;
import dev.wasdevv.hdb.fhir.validation.ResourceValidator;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class FhirConfig {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    FhirContext fhirContext() {
        return FhirContext.forR4Cached();
    }

    @Bean
    ResourceValidator resourceValidator(FhirContext ctx) {
        return new ResourceValidator(ctx);
    }

    @Bean
    ResourceStore resourceStore(JdbcTemplate jdbc, FhirContext ctx, ResourceValidator validator,
                                ObjectProvider<ResourceWriteListener> listeners, Clock clock) {
        return new ResourceStore(jdbc, ctx, validator, listeners.orderedStream().toList(), clock);
    }

    @Bean
    TransactionProcessor transactionProcessor(ResourceStore store, FhirContext ctx) {
        return new TransactionProcessor(store, ctx);
    }

    @Bean
    ServletRegistrationBean<RestfulServer> fhirServlet(FhirContext ctx, ResourceStore store,
                                                        TransactionProcessor transactions) {
        RestfulServer server = new RestfulServer(ctx);
        server.setServerName("Health Data Bridge");
        server.setImplementationDescription("FHIR R4 facade over PostgreSQL that bills finished encounters in an ERP");
        server.setDefaultResponseEncoding(EncodingEnum.JSON);
        server.setResourceProviders(List.copyOf(ResourceProviders.all(store)));
        server.registerProvider(new TransactionProvider(transactions));
        FifoMemoryPagingProvider paging = new FifoMemoryPagingProvider(200);
        paging.setDefaultPageSize(DEFAULT_PAGE_SIZE);
        paging.setMaximumPageSize(MAX_PAGE_SIZE);
        server.setPagingProvider(paging);
        ServletRegistrationBean<RestfulServer> registration = new ServletRegistrationBean<>(server, "/fhir/*");
        registration.setName("fhir");
        registration.setLoadOnStartup(1);
        return registration;
    }
}
