package com.taskqueue.worker;

import com.taskqueue.domain.Job;
import com.taskqueue.domain.JobStatus;
import com.taskqueue.repository.JobRepository;
import com.taskqueue.service.QueueService;
import com.taskqueue.service.TenantConcurrencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LeaseReaper {

    private final JobRepository jobRepository;
    private final LeaseManager leaseManager;
    private final QueueService queueService;
    private final TenantConcurrencyService tenantConcurrencyService;

    @Scheduled(fixedDelayString = "${taskqueue.worker.lease-reaper-interval-seconds:15}000")
    @Transactional
    public void reapExpiredLeases() {
        log.debug("Lease reaper running...");

        LocalDateTime now = LocalDateTime.now();
        List<Job> expiredJobs = jobRepository.findExpiredLeases(now);

        if (expiredJobs.isEmpty()) {
            log.debug("No expired leases found");
            return;
        }

        log.info("Found {} potentially expired lease jobs", expiredJobs.size());

        for (Job job : expiredJobs) {
            String jobIdStr = job.getId().toString();
            MDC.put("jobId", jobIdStr);
            MDC.put("tenantId", job.getTenantId());

            try {
                // Double-check: lease should not exist in Redis
                if (!leaseManager.exists(jobIdStr)) {
                    log.warn("Job {} has RUNNING status but no Redis lease — recovering to PENDING",
                        jobIdStr);

                    job.setStatus(JobStatus.PENDING);
                    job.setLockedBy(null);
                    job.setLockedUntil(null);
                    job.setStartedAt(null);
                    job.setAttempt(job.getAttempt() + 1);
                    jobRepository.save(job);

                    // The crashed worker never released its concurrency slot — decrement now
                    // so the recovered job can be picked up without permanently shrinking the quota.
                    tenantConcurrencyService.releaseSlot(job.getTenantId());

                    long executeAt = Instant.now().toEpochMilli();
                    queueService.enqueue(jobIdStr, executeAt);

                    log.info("Re-queued recovered job {} for tenant {}", jobIdStr, job.getTenantId());
                } else {
                    log.debug("Job {} still has active lease — skipping", jobIdStr);
                }
            } catch (Exception e) {
                log.error("Error during lease reaper for job {}: {}", jobIdStr, e.getMessage(), e);
            } finally {
                MDC.remove("jobId");
                MDC.remove("tenantId");
            }
        }
    }
}
