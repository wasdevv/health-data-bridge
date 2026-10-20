package dev.wasdevv.hdb.sync;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code bridge.sync.*}. Defaults are the PRD's: breaker opens at 50% failures over a window of 20
 * calls and stays open 30 s; at most 8 attempts with exponential backoff and jitter.
 */
@ConfigurationProperties("bridge.sync")
public record SyncProperties(
        String erpBaseUrl,
        Duration erpConnectTimeout,
        Duration erpReadTimeout,
        boolean workerEnabled,
        Duration pollInterval,
        int batchSize,
        int concurrency,
        int maxAttempts,
        Duration backoffBase,
        Duration backoffMax,
        float breakerFailureRate,
        int breakerWindow,
        Duration breakerOpenWait,
        int breakerHalfOpenCalls) {

    public SyncProperties {
        erpBaseUrl = erpBaseUrl == null ? "http://localhost:8090" : erpBaseUrl;
        erpConnectTimeout = erpConnectTimeout == null ? Duration.ofSeconds(2) : erpConnectTimeout;
        erpReadTimeout = erpReadTimeout == null ? Duration.ofSeconds(5) : erpReadTimeout;
        pollInterval = pollInterval == null ? Duration.ofSeconds(1) : pollInterval;
        batchSize = batchSize <= 0 ? 50 : batchSize;
        concurrency = concurrency <= 0 ? 4 : concurrency;
        maxAttempts = maxAttempts <= 0 ? 8 : maxAttempts;
        backoffBase = backoffBase == null ? Duration.ofSeconds(1) : backoffBase;
        backoffMax = backoffMax == null ? Duration.ofMinutes(5) : backoffMax;
        breakerFailureRate = breakerFailureRate <= 0 ? 50f : breakerFailureRate;
        breakerWindow = breakerWindow <= 0 ? 20 : breakerWindow;
        breakerOpenWait = breakerOpenWait == null ? Duration.ofSeconds(30) : breakerOpenWait;
        breakerHalfOpenCalls = breakerHalfOpenCalls <= 0 ? 5 : breakerHalfOpenCalls;
    }
}
