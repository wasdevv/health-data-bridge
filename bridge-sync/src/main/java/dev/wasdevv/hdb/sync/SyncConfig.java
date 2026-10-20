package dev.wasdevv.hdb.sync;

import dev.wasdevv.hdb.fhir.store.ResourceStore;
import dev.wasdevv.hdb.fhir.validation.ResourceValidator;
import dev.wasdevv.hdb.sync.erp.ErpCircuitBreaker;
import dev.wasdevv.hdb.sync.erp.ErpClient;
import dev.wasdevv.hdb.sync.outbox.InvoicePreparer;
import dev.wasdevv.hdb.sync.outbox.OutboxEnqueuer;
import dev.wasdevv.hdb.sync.outbox.OutboxMetrics;
import dev.wasdevv.hdb.sync.outbox.OutboxRepository;
import dev.wasdevv.hdb.sync.outbox.OutboxWorker;
import dev.wasdevv.hdb.sync.outbox.RetryPolicy;
import dev.wasdevv.hdb.sync.reconciliation.Reconciliation;
import dev.wasdevv.hdb.sync.registry.RegistrySync;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SyncProperties.class)
public class SyncConfig {

    @Bean
    OutboxRepository outboxRepository(JdbcTemplate jdbc) {
        return new OutboxRepository(jdbc);
    }

    @Bean
    OutboxEnqueuer outboxEnqueuer(OutboxRepository outbox, Clock clock) {
        return new OutboxEnqueuer(outbox, clock);
    }

    @Bean
    ErpClient erpClient(RestClient.Builder builder, SyncProperties p) {
        // HTTP/1.1: the JDK client's default h2c upgrade on plain http is reset by some servers mid-request
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(p.erpConnectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(p.erpReadTimeout());
        return new ErpClient(builder.baseUrl(p.erpBaseUrl()).requestFactory(factory).build());
    }

    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry(MeterRegistry meters) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        return registry;
    }

    @Bean
    CircuitBreaker erpCircuitBreaker(CircuitBreakerRegistry registry, SyncProperties p, Clock clock) {
        return ErpCircuitBreaker.create(registry, p, clock);
    }

    @Bean
    RetryPolicy retryPolicy(SyncProperties p) {
        return new RetryPolicy(p.backoffBase(), p.backoffMax(), p.maxAttempts(), new SecureRandom());
    }

    @Bean
    InvoicePreparer invoicePreparer(ResourceStore store, ResourceValidator validator) {
        return new InvoicePreparer(store, validator);
    }

    @Bean
    OutboxWorker outboxWorker(OutboxRepository outbox, InvoicePreparer preparer, ErpClient erp, CircuitBreaker breaker,
                              RetryPolicy retry, TransactionTemplate tx, Clock clock, SyncProperties p,
                              MeterRegistry meters) {
        OutboxMetrics.bind(meters, outbox, clock);
        return new OutboxWorker(outbox, preparer, erp, breaker, retry, tx, clock, p.batchSize(), p.concurrency(), meters);
    }

    @Bean
    RegistrySync registrySync(ErpClient erp, ResourceStore store, JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        return new RegistrySync(erp, store, jdbc, tx, clock);
    }

    @Bean
    Reconciliation reconciliation(ResourceStore store, ErpClient erp, OutboxRepository outbox, JdbcTemplate jdbc,
                                  TransactionTemplate tx, Clock clock) {
        return new Reconciliation(store, erp, outbox, jdbc, tx, clock);
    }
}
