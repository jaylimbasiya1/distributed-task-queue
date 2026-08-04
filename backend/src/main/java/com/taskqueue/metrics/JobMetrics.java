package com.taskqueue.metrics;

import com.taskqueue.service.QueueService;
import com.taskqueue.worker.WorkerPool;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class JobMetrics {

    private final MeterRegistry meterRegistry;
    private final QueueService queueService;
    private final WorkerPool workerPool;

    private final Map<String, Counter> submittedCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> completedCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> failedCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> dlqCounters = new ConcurrentHashMap<>();
    private final Map<String, Timer> processingTimers = new ConcurrentHashMap<>();

    public JobMetrics(MeterRegistry meterRegistry,
                      QueueService queueService,
                      @Lazy WorkerPool workerPool) {
        this.meterRegistry = meterRegistry;
        this.queueService = queueService;
        this.workerPool = workerPool;
    }

    @PostConstruct
    public void init() {
        Gauge.builder("taskqueue.queue.depth", queueService, QueueService::depth)
            .description("Current depth of the task queue")
            .register(meterRegistry);

        Gauge.builder("taskqueue.workers.active", workerPool, WorkerPool::getActiveCount)
            .description("Number of currently active workers")
            .register(meterRegistry);

        log.info("JobMetrics initialized");
    }

    public void recordJobSubmitted(String tenantId) {
        submittedCounters.computeIfAbsent(tenantId, t ->
            Counter.builder("taskqueue.jobs.submitted")
                .tag("tenant", t)
                .description("Number of jobs submitted")
                .register(meterRegistry)
        ).increment();
    }

    public void recordJobCompleted(String tenantId, long durationMs) {
        completedCounters.computeIfAbsent(tenantId, t ->
            Counter.builder("taskqueue.jobs.completed")
                .tag("tenant", t)
                .description("Number of jobs completed")
                .register(meterRegistry)
        ).increment();

        processingTimers.computeIfAbsent(tenantId, t ->
            Timer.builder("taskqueue.worker.processing.time")
                .tag("tenant", t)
                .description("Job processing time")
                .register(meterRegistry)
        ).record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordJobFailed(String tenantId) {
        failedCounters.computeIfAbsent(tenantId, t ->
            Counter.builder("taskqueue.jobs.failed")
                .tag("tenant", t)
                .description("Number of jobs failed")
                .register(meterRegistry)
        ).increment();
    }

    public void recordJobDLQ(String tenantId) {
        dlqCounters.computeIfAbsent(tenantId, t ->
            Counter.builder("taskqueue.jobs.dlq")
                .tag("tenant", t)
                .description("Number of jobs moved to DLQ")
                .register(meterRegistry)
        ).increment();
    }
}
