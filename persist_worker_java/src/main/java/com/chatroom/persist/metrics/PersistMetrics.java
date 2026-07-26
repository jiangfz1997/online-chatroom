package com.chatroom.persist.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Single Micrometer facade for persist-worker, exposed via Actuator at /actuator/prometheus.
 * Covers the two persist-side metrics from the M2a instrumentation-first plan: DynamoDB write
 * latency and the Redis to_persist backlog depth.
 */
@Component
public class PersistMetrics {

    private final AtomicLong backlog = new AtomicLong(0);

    private final Counter persistedCounter;
    private final Counter malformedCounter;
    private final Timer dynamoWriteTimer;

    public PersistMetrics(MeterRegistry registry) {
        registry.gauge("persist.queue.backlog", backlog);

        this.persistedCounter = Counter.builder("persist.messages.persisted")
                .description("Messages successfully written to DynamoDB")
                .register(registry);
        this.malformedCounter = Counter.builder("persist.messages.malformed")
                .description("Messages popped from the to_persist queue but skipped as malformed")
                .register(registry);
        this.dynamoWriteTimer = Timer.builder("persist.dynamodb.write.duration")
                .description("Latency of a single DynamoDB write (PutItem, batched later in M2b)")
                .publishPercentileHistogram()
                .register(registry);
    }

    /** Snapshot the to_persist backlog (sum of LLEN across active rooms) ahead of a drain tick. */
    public void setBacklog(long value) {
        backlog.set(value);
    }

    public void messagePersisted() {
        persistedCounter.increment();
    }

    public void messageMalformed() {
        malformedCounter.increment();
    }

    public void recordDynamoWrite(Runnable write) {
        dynamoWriteTimer.record(write);
    }
}
