package com.taskqueue.service;

import com.taskqueue.domain.DLQEntry;
import com.taskqueue.domain.Job;
import com.taskqueue.domain.JobStatus;
import com.taskqueue.domain.Tenant;
import com.taskqueue.dto.JobSubmitRequest;
import com.taskqueue.dto.JobResponse;
import com.taskqueue.repository.DLQEntryRepository;
import com.taskqueue.repository.JobRepository;
import com.taskqueue.security.TenantContext;
import com.taskqueue.websocket.JobStatusBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRepository jobRepository;
    private final DLQEntryRepository dlqEntryRepository;
    private final QueueService queueService;
    private final RateLimiterService rateLimiterService;
    private final JobStatusBroadcaster broadcaster;

    @Transactional
    public JobResponse submitJob(JobSubmitRequest req) {
        Tenant tenant = TenantContext.get();
        if (tenant == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        }

        MDC.put("tenantId", tenant.getId());

        try {
            // Check idempotency key
            if (req.idempotencyKey() != null && !req.idempotencyKey().isBlank()) {
                var existing = jobRepository.findByTenantIdAndIdempotencyKey(
                    tenant.getId(), req.idempotencyKey());
                if (existing.isPresent()) {
                    log.info("Duplicate idempotency key {} for tenant {} — returning existing job",
                        req.idempotencyKey(), tenant.getId());
                    return JobResponse.from(existing.get());
                }
            }

            // Check rate limit
            if (!rateLimiterService.isAllowed(tenant.getId(), tenant.getRateLimitPerMinute())) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Rate limit exceeded for tenant: " + tenant.getId());
            }

            // Build the job
            int maxRetries = req.maxRetries() > 0 ? req.maxRetries() : tenant.getMaxRetriesDefault();
            long executeAt = System.currentTimeMillis() + Math.max(0, req.delayMs());
            LocalDateTime scheduledAt = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(executeAt), java.time.ZoneId.systemDefault());

            Map<String, Object> payload = new HashMap<>(req.payload());

            Job job = Job.builder()
                .tenantId(tenant.getId())
                .type(req.type())
                .payload(payload)
                .status(JobStatus.PENDING)
                .attempt(0)
                .maxRetries(maxRetries)
                .idempotencyKey(req.idempotencyKey())
                .scheduledAt(scheduledAt)
                .build();

            job = jobRepository.save(job);
            MDC.put("jobId", job.getId().toString());

            // Enqueue AFTER commit so workers never see a job ID before the row exists in DB
            final String jobId = job.getId().toString();
            final long scheduleAt = executeAt;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { queueService.enqueue(jobId, scheduleAt); }
            });
            broadcaster.broadcast(job);

            log.info("Submitted job {} of type {} for tenant {}", job.getId(), job.getType(), tenant.getId());
            return JobResponse.from(job);
        } finally {
            MDC.remove("jobId");
            MDC.remove("tenantId");
        }
    }

    @Transactional(readOnly = true)
    public JobResponse getJob(UUID id) {
        Tenant tenant = TenantContext.get();
        if (tenant == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        }

        Job job = jobRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + id));

        if (!job.getTenantId().equals(tenant.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Job does not belong to tenant");
        }

        return JobResponse.from(job);
    }

    @Transactional(readOnly = true)
    public Page<JobResponse> listJobs(JobStatus status, Pageable pageable) {
        Tenant tenant = TenantContext.get();
        if (tenant == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        }

        Page<Job> jobs;
        if (status != null) {
            jobs = jobRepository.findByTenantIdAndStatus(tenant.getId(), status, pageable);
        } else {
            jobs = jobRepository.findByTenantId(tenant.getId(), pageable);
        }

        return jobs.map(JobResponse::from);
    }

    @Transactional
    public JobResponse cancelJob(UUID id) {
        Tenant tenant = TenantContext.get();
        if (tenant == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        }

        Job job = jobRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + id));

        if (!job.getTenantId().equals(tenant.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Job does not belong to tenant");
        }

        if (job.getStatus() != JobStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only PENDING jobs can be cancelled. Current status: " + job.getStatus());
        }

        job.setStatus(JobStatus.CANCELLED);
        job = jobRepository.save(job);
        queueService.remove(job.getId().toString());
        broadcaster.broadcast(job);

        log.info("Cancelled job {} for tenant {}", id, tenant.getId());
        return JobResponse.from(job);
    }

    @Transactional
    public JobResponse retryJob(UUID id) {
        Tenant tenant = TenantContext.get();
        if (tenant == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        }

        Job job = jobRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + id));

        if (!job.getTenantId().equals(tenant.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Job does not belong to tenant");
        }

        job.setStatus(JobStatus.PENDING);
        job.setAttempt(0);
        job.setLastError(null);
        job.setStartedAt(null);
        job.setCompletedAt(null);
        job.setLockedBy(null);
        job.setLockedUntil(null);
        job.setScheduledAt(LocalDateTime.now());

        job = jobRepository.save(job);
        final String retryJobId = job.getId().toString();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { queueService.enqueue(retryJobId, System.currentTimeMillis()); }
        });
        broadcaster.broadcast(job);

        log.info("Retried job {} for tenant {}", id, tenant.getId());
        return JobResponse.from(job);
    }
}
