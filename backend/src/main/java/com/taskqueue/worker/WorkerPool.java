package com.taskqueue.worker;

import com.taskqueue.config.AppProperties;
import com.taskqueue.domain.DLQEntry;
import com.taskqueue.domain.Job;
import com.taskqueue.domain.JobStatus;
import com.taskqueue.domain.Tenant;
import com.taskqueue.metrics.JobMetrics;
import com.taskqueue.repository.DLQEntryRepository;
import com.taskqueue.repository.JobRepository;
import com.taskqueue.repository.TenantRepository;
import com.taskqueue.service.QueueService;
import com.taskqueue.service.TenantConcurrencyService;
import com.taskqueue.websocket.JobStatusBroadcaster;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerPool {

    private final QueueService queueService;
    private final JobRepository jobRepository;
    private final DLQEntryRepository dlqEntryRepository;
    private final TenantRepository tenantRepository;
    private final LeaseManager leaseManager;
    private final JobStatusBroadcaster broadcaster;
    private final AppProperties appProperties;
    private final JobMetrics jobMetrics;
    private final TenantConcurrencyService tenantConcurrencyService;

    private ThreadPoolTaskExecutor executor;
    private ScheduledExecutorService renewalScheduler;
    private volatile boolean running = true;
    private final AtomicInteger activeWorkers = new AtomicInteger(0);
    private final AtomicInteger activeJobCount = new AtomicInteger(0);

    @PostConstruct
    public void init() {
        int minSize = appProperties.getWorker().getMinPoolSize();
        int maxSize = appProperties.getWorker().getMaxPoolSize();

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(minSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("worker-");
        executor.initialize();

        renewalScheduler = Executors.newScheduledThreadPool(maxSize);

        // Sync Redis concurrency counters from DB so jobs that were RUNNING when the app
        // last shut down are counted before the LeaseReaper has a chance to clean them up.
        try {
            jobRepository.countRunningByTenant().forEach(row -> {
                String tenantId = (String) row[0];
                long count = ((Number) row[1]).longValue();
                tenantConcurrencyService.seedCount(tenantId, count);
            });
        } catch (Exception e) {
            log.warn("Could not seed concurrency counters from DB — counters start at 0: {}", e.getMessage());
        }

        for (int i = 0; i < minSize; i++) {
            executor.execute(this::pollLoop);
        }

        log.info("WorkerPool started with {} threads", minSize);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (renewalScheduler != null) {
            renewalScheduler.shutdownNow();
        }
        if (executor != null) {
            executor.shutdown();
        }
        log.info("WorkerPool shut down");
    }

    private void pollLoop() {
        long pollIntervalMs = appProperties.getWorker().getPollIntervalMs();
        while (running) {
            try {
                String jobId = queueService.poll();
                if (jobId != null) {
                    process(jobId);
                } else {
                    Thread.sleep(pollIntervalMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Worker thread interrupted, stopping poll loop");
                break;
            } catch (Exception e) {
                log.error("Unexpected error in poll loop: {}", e.getMessage(), e);
            }
        }
    }

    private void process(String jobId) {
        MDC.put("jobId", jobId);
        try {
            Optional<Job> jobOpt = jobRepository.findById(UUID.fromString(jobId));
            if (jobOpt.isEmpty()) {
                // Guard against the rare race where the enqueuing transaction hasn't committed yet
                Thread.sleep(200);
                jobOpt = jobRepository.findById(UUID.fromString(jobId));
                if (jobOpt.isEmpty()) {
                    log.warn("Job {} not found in DB after retry, skipping", jobId);
                    return;
                }
            }

            Job job = jobOpt.get();
            MDC.put("tenantId", job.getTenantId());

            // Check if already in terminal state
            if (job.getStatus() == JobStatus.COMPLETED
                || job.getStatus() == JobStatus.CANCELLED
                || job.getStatus() == JobStatus.DLQ) {
                log.debug("Job {} is in terminal state {}, skipping", jobId, job.getStatus());
                return;
            }

            // Acquire lease first — same-job deduplication across workers
            String workerId = Thread.currentThread().getName();
            if (!leaseManager.acquire(jobId, workerId)) {
                log.debug("Could not acquire lease for job {} — another worker picked it up", jobId);
                return;
            }

            // Atomic concurrency check via Redis INCR.
            // Two workers checking a DB count simultaneously both see the same value and both
            // proceed — a classic check-then-act race. Redis INCR is atomic: only the worker
            // whose increment stays within the limit proceeds; the others roll back and re-queue.
            int maxConcurrentJobs = tenantRepository.findById(job.getTenantId())
                .map(Tenant::getMaxConcurrentJobs)
                .orElse(Integer.MAX_VALUE);
            if (!tenantConcurrencyService.tryAcquireSlot(job.getTenantId(), maxConcurrentJobs)) {
                leaseManager.release(jobId);
                log.debug("Tenant {} at concurrency limit, re-queuing job {}", job.getTenantId(), jobId);
                queueService.enqueue(jobId, System.currentTimeMillis() + 1000L);
                return;
            }
            // Slot acquired — releaseSlot() is called in the finally block below.

            int ttl = appProperties.getWorker().getLeaseTtlSeconds();
            // Renew at half-TTL intervals so the lease never expires during normal execution
            long renewIntervalMs = Math.max(1000L, (ttl / 2) * 1000L);
            ScheduledFuture<?> renewalTask = null;

            try {
                // Mark as RUNNING in DB
                job.setStatus(JobStatus.RUNNING);
                job.setStartedAt(LocalDateTime.now());
                job.setLockedBy(workerId);
                job.setLockedUntil(LocalDateTime.now().plusSeconds(ttl));
                jobRepository.save(job);
                broadcaster.broadcast(job);

                activeJobCount.incrementAndGet();
                log.info("Processing job {} of type {} for tenant {}", jobId, job.getType(), job.getTenantId());

                // Periodically reset the lease TTL so long-running jobs don't lose their lease
                final String capturedJobId = jobId;
                final String capturedWorkerId = workerId;
                renewalTask = renewalScheduler.scheduleAtFixedRate(
                    () -> {
                        if (!leaseManager.renew(capturedJobId, capturedWorkerId, ttl)) {
                            log.warn("Lease renewal failed for job {} by worker {} — lease may have been reclaimed",
                                capturedJobId, capturedWorkerId);
                        }
                    },
                    renewIntervalMs, renewIntervalMs, TimeUnit.MILLISECONDS
                );

                long startTime = System.currentTimeMillis();
                executeJob(job);
                long durationMs = System.currentTimeMillis() - startTime;

                // Stop renewing before the ownership check
                renewalTask.cancel(false);
                renewalTask = null;

                // Guard against the race: if the lease expired and LeaseReaper already re-queued
                // this job, another worker may now hold the lease. Marking COMPLETED here would
                // silently discard that second execution's result and leave the job in a wrong state.
                if (!leaseManager.isHeldBy(jobId, workerId)) {
                    log.warn("Job {} lease was reclaimed during execution by worker {} — discarding result to avoid duplicate completion",
                        jobId, workerId);
                    return;
                }

                // Mark COMPLETED
                leaseManager.release(jobId);
                job.setStatus(JobStatus.COMPLETED);
                job.setCompletedAt(LocalDateTime.now());
                job.setLockedBy(null);
                job.setLockedUntil(null);
                jobRepository.save(job);
                broadcaster.broadcast(job);

                jobMetrics.recordJobCompleted(job.getTenantId(), durationMs);
                log.info("Completed job {} for tenant {} in {}ms", jobId, job.getTenantId(), durationMs);

            } catch (Exception e) {
                log.error("Job {} failed: {}", jobId, e.getMessage(), e);
                leaseManager.release(jobId);
                handleFailure(job, e.getMessage());
            } finally {
                if (renewalTask != null) {
                    renewalTask.cancel(false);
                }
                tenantConcurrencyService.releaseSlot(job.getTenantId());
                activeJobCount.decrementAndGet();
            }

        } catch (Exception e) {
            log.error("Unexpected error processing job {}: {}", jobId, e.getMessage(), e);
        } finally {
            MDC.remove("jobId");
            MDC.remove("tenantId");
        }
    }

    private void executeJob(Job job) throws Exception {
        // Simulate execution based on payload duration field
        Object durationObj = job.getPayload().get("durationMs");
        long duration = 1000L;
        if (durationObj instanceof Number) {
            duration = ((Number) durationObj).longValue();
        }

        // Check for simulated failure
        Object failureRateObj = job.getPayload().get("failureRate");
        if (failureRateObj instanceof Number) {
            double failureRate = ((Number) failureRateObj).doubleValue();
            if (Math.random() < failureRate) {
                throw new RuntimeException("Simulated job failure (failureRate=" + failureRate + ")");
            }
        }

        Thread.sleep(duration);
    }

    private void handleFailure(Job job, String errorMessage) {
        String jobId = job.getId().toString();
        int nextAttempt = job.getAttempt() + 1;
        job.setAttempt(nextAttempt);
        job.setLastError(errorMessage);
        job.setLockedBy(null);
        job.setLockedUntil(null);

        if (nextAttempt < job.getMaxRetries()) {
            // Exponential backoff: delay = baseDelay * 2^attempt
            long baseDelay = appProperties.getRetry().getBaseDelayMs();
            long maxDelay = appProperties.getRetry().getMaxDelayMs();
            long delay = Math.min(baseDelay * (1L << nextAttempt), maxDelay);

            job.setStatus(JobStatus.PENDING);
            jobRepository.save(job);
            broadcaster.broadcast(job);

            long executeAt = System.currentTimeMillis() + delay;
            queueService.enqueue(jobId, executeAt);

            log.info("Re-queued job {} attempt {}/{} with backoff {}ms",
                jobId, nextAttempt, job.getMaxRetries(), delay);
        } else {
            // Move to DLQ
            job.setStatus(JobStatus.DLQ);
            jobRepository.save(job);
            broadcaster.broadcast(job);

            DLQEntry dlqEntry = DLQEntry.builder()
                .jobId(job.getId())
                .tenantId(job.getTenantId())
                .type(job.getType())
                .payload(job.getPayload())
                .totalAttempts(nextAttempt)
                .lastError(errorMessage)
                .build();
            dlqEntryRepository.save(dlqEntry);

            jobMetrics.recordJobDLQ(job.getTenantId());
            log.warn("Job {} moved to DLQ after {} attempts", jobId, nextAttempt);
        }

        jobMetrics.recordJobFailed(job.getTenantId());
    }

    public int getActiveCount() {
        return activeJobCount.get();
    }

    public int getCurrentPoolSize() {
        return executor != null ? executor.getPoolSize() : 0;
    }

    public void setPoolSize(int size) {
        if (executor != null) {
            int maxSize = appProperties.getWorker().getMaxPoolSize();
            int minSize = appProperties.getWorker().getMinPoolSize();
            int newSize = Math.max(minSize, Math.min(maxSize, size));
            int currentSize = executor.getPoolSize();

            if (newSize > currentSize) {
                executor.setMaxPoolSize(newSize);
                executor.setCorePoolSize(newSize);
                int diff = newSize - currentSize;
                for (int i = 0; i < diff; i++) {
                    executor.execute(this::pollLoop);
                }
                log.info("Scaled up worker pool from {} to {}", currentSize, newSize);
            } else if (newSize < currentSize) {
                executor.setCorePoolSize(newSize);
                log.info("Scaled down worker pool target from {} to {}", currentSize, newSize);
            }
        }
    }
}
