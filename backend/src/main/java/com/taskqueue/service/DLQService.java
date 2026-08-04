package com.taskqueue.service;

import com.taskqueue.domain.DLQEntry;
import com.taskqueue.domain.Job;
import com.taskqueue.domain.JobStatus;
import com.taskqueue.domain.Tenant;
import com.taskqueue.repository.DLQEntryRepository;
import com.taskqueue.repository.JobRepository;
import com.taskqueue.security.TenantContext;
import com.taskqueue.websocket.JobStatusBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DLQService {

    private final DLQEntryRepository dlqEntryRepository;
    private final JobRepository jobRepository;
    private final QueueService queueService;
    private final JobStatusBroadcaster broadcaster;

    @Transactional(readOnly = true)
    public List<DLQEntry> listDLQ() {
        Tenant tenant = TenantContext.get();
        if (tenant == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        }
        return dlqEntryRepository.findByTenantId(tenant.getId());
    }

    @Transactional
    public void purgeDLQ(UUID id) {
        Tenant tenant = TenantContext.get();
        if (tenant == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        }

        DLQEntry entry = dlqEntryRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "DLQ entry not found: " + id));

        if (!entry.getTenantId().equals(tenant.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DLQ entry does not belong to tenant");
        }

        dlqEntryRepository.delete(entry);
        log.info("Purged DLQ entry {} for tenant {}", id, tenant.getId());
    }

    @Transactional
    public Job retryFromDLQ(UUID id) {
        Tenant tenant = TenantContext.get();
        if (tenant == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        }

        DLQEntry entry = dlqEntryRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "DLQ entry not found: " + id));

        if (!entry.getTenantId().equals(tenant.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DLQ entry does not belong to tenant");
        }

        // Mark the original job as CANCELLED so it no longer counts in DLQ metrics
        // Also capture its maxRetries so the new job inherits the same retry budget
        final int[] originalMaxRetries = {3};
        jobRepository.findById(entry.getJobId()).ifPresent(original -> {
            originalMaxRetries[0] = original.getMaxRetries();
            original.setStatus(JobStatus.CANCELLED);
            jobRepository.save(original);
            broadcaster.broadcast(original);
        });

        // Create a fresh job — new ID, attempt reset to 0, same payload and retry budget
        Job job = Job.builder()
            .tenantId(entry.getTenantId())
            .type(entry.getType())
            .payload(new HashMap<>(entry.getPayload()))
            .status(JobStatus.PENDING)
            .attempt(0)
            .maxRetries(originalMaxRetries[0])
            .scheduledAt(LocalDateTime.now())
            .build();

        job = jobRepository.save(job);
        final String dlqRetryJobId = job.getId().toString();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { queueService.enqueue(dlqRetryJobId, System.currentTimeMillis()); }
        });
        broadcaster.broadcast(job);

        dlqEntryRepository.delete(entry);
        log.info("Retried DLQ entry {} as new job {} for tenant {}", id, job.getId(), tenant.getId());

        return job;
    }
}
