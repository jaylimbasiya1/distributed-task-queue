package com.taskqueue.test;

import com.taskqueue.domain.Job;
import com.taskqueue.domain.JobStatus;
import com.taskqueue.domain.Tenant;
import com.taskqueue.repository.JobRepository;
import com.taskqueue.repository.TenantRepository;
import com.taskqueue.service.QueueService;
import com.taskqueue.worker.LeaseManager;
import com.taskqueue.worker.LeaseReaper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LeaseExpiryTest {

    private JobRepository jobRepository;
    private LeaseManager leaseManager;
    private QueueService queueService;
    private LeaseReaper leaseReaper;

    @BeforeEach
    void setUp() {
        jobRepository = Mockito.mock(JobRepository.class);
        leaseManager = Mockito.mock(LeaseManager.class);
        queueService = Mockito.mock(QueueService.class);
        leaseReaper = new LeaseReaper(jobRepository, leaseManager, queueService);
    }

    private Job buildRunningJob(String id, String tenantId) {
        Job job = new Job();
        job.setId(java.util.UUID.fromString(id));
        job.setTenantId(tenantId);
        job.setType("TEST");
        job.setPayload(Map.of("test", "data"));
        job.setStatus(JobStatus.RUNNING);
        job.setAttempt(0);
        job.setMaxRetries(3);
        job.setLockedBy("old-worker");
        job.setLockedUntil(LocalDateTime.now().minusSeconds(60));
        job.setScheduledAt(LocalDateTime.now().minusMinutes(5));
        job.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        job.setUpdatedAt(LocalDateTime.now().minusSeconds(60));
        return job;
    }

    @Test
    void shouldRequeueJobWithExpiredLease() {
        String jobId = "a0000000-0000-0000-0000-000000000001";
        Job job = buildRunningJob(jobId, "tenant-1");

        when(jobRepository.findExpiredLeases(any(LocalDateTime.class))).thenReturn(List.of(job));
        when(leaseManager.exists(jobId)).thenReturn(false);
        when(jobRepository.save(any(Job.class))).thenReturn(job);

        leaseReaper.reapExpiredLeases();

        verify(jobRepository).save(argThat(j ->
            j.getStatus() == JobStatus.PENDING
            && j.getLockedBy() == null
            && j.getAttempt() == 1
        ));
        verify(queueService).enqueue(eq(jobId), anyLong());
    }

    @Test
    void shouldNotRequeueJobWithActiveLease() {
        String jobId = "a0000000-0000-0000-0000-000000000002";
        Job job = buildRunningJob(jobId, "tenant-1");

        when(jobRepository.findExpiredLeases(any(LocalDateTime.class))).thenReturn(List.of(job));
        when(leaseManager.exists(jobId)).thenReturn(true);

        leaseReaper.reapExpiredLeases();

        verify(jobRepository, never()).save(any());
        verify(queueService, never()).enqueue(anyString(), anyLong());
    }

    @Test
    void shouldHandleMultipleExpiredJobs() {
        String jobId1 = "a0000000-0000-0000-0000-000000000003";
        String jobId2 = "a0000000-0000-0000-0000-000000000004";
        Job job1 = buildRunningJob(jobId1, "tenant-1");
        Job job2 = buildRunningJob(jobId2, "tenant-2");

        when(jobRepository.findExpiredLeases(any(LocalDateTime.class))).thenReturn(List.of(job1, job2));
        when(leaseManager.exists(jobId1)).thenReturn(false);
        when(leaseManager.exists(jobId2)).thenReturn(false);
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        leaseReaper.reapExpiredLeases();

        verify(queueService, times(2)).enqueue(anyString(), anyLong());
        verify(jobRepository, times(2)).save(any(Job.class));
    }

    @Test
    void shouldDoNothingWhenNoExpiredLeases() {
        when(jobRepository.findExpiredLeases(any(LocalDateTime.class))).thenReturn(List.of());

        leaseReaper.reapExpiredLeases();

        verify(queueService, never()).enqueue(anyString(), anyLong());
        verify(jobRepository, never()).save(any());
    }

    @Test
    void shouldIncrementAttemptCountOnRecovery() {
        String jobId = "a0000000-0000-0000-0000-000000000005";
        Job job = buildRunningJob(jobId, "tenant-1");
        job.setAttempt(2);

        when(jobRepository.findExpiredLeases(any(LocalDateTime.class))).thenReturn(List.of(job));
        when(leaseManager.exists(jobId)).thenReturn(false);
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        leaseReaper.reapExpiredLeases();

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        assertEquals(3, captor.getValue().getAttempt());
    }
}
