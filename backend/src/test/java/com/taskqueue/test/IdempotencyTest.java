package com.taskqueue.test;

import com.taskqueue.domain.Job;
import com.taskqueue.domain.JobStatus;
import com.taskqueue.domain.Tenant;
import com.taskqueue.dto.JobResponse;
import com.taskqueue.dto.JobSubmitRequest;
import com.taskqueue.repository.JobRepository;
import com.taskqueue.repository.TenantRepository;
import com.taskqueue.security.TenantContext;
import com.taskqueue.service.JobService;
import com.taskqueue.service.QueueService;
import com.taskqueue.service.RateLimiterService;
import com.taskqueue.websocket.JobStatusBroadcaster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
class IdempotencyTest {

    @Autowired
    private JobService jobService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @MockBean
    private QueueService queueService;

    @MockBean
    private RateLimiterService rateLimiterService;

    @MockBean
    private JobStatusBroadcaster broadcaster;

    private Tenant tenant;

    @BeforeEach
    void setUp() {
        when(rateLimiterService.isAllowed(anyString(), anyInt())).thenReturn(true);
        doNothing().when(queueService).enqueue(anyString(), anyLong());
        doNothing().when(broadcaster).broadcast(any(Job.class));

        tenant = Tenant.builder()
            .id("test-tenant")
            .name("Test Tenant")
            .apiKey("test-api-key-" + System.nanoTime())
            .rateLimitPerMinute(100)
            .maxConcurrentJobs(10)
            .maxRetriesDefault(3)
            .requireIdempotencyKey(false)
            .build();
        tenant = tenantRepository.save(tenant);
        TenantContext.set(tenant);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        jobRepository.deleteAll();
        tenantRepository.deleteAll();
    }

    @Test
    void shouldReturnExistingJobForDuplicateIdempotencyKey() {
        String idempotencyKey = "unique-key-123";
        Map<String, Object> payload = Map.of("task", "send-email", "durationMs", 100);

        JobSubmitRequest request1 = new JobSubmitRequest("EMAIL", payload, idempotencyKey, 3, 0);
        JobResponse response1 = jobService.submitJob(request1);

        JobSubmitRequest request2 = new JobSubmitRequest("EMAIL", payload, idempotencyKey, 3, 0);
        JobResponse response2 = jobService.submitJob(request2);

        assertEquals(response1.id(), response2.id(),
            "Duplicate idempotency key should return the same job ID");
        verify(queueService, times(1)).enqueue(anyString(), anyLong());
    }

    @Test
    void shouldCreateNewJobForDifferentIdempotencyKey() {
        Map<String, Object> payload = Map.of("task", "send-email", "durationMs", 100);

        JobSubmitRequest request1 = new JobSubmitRequest("EMAIL", payload, "key-001", 3, 0);
        JobResponse response1 = jobService.submitJob(request1);

        JobSubmitRequest request2 = new JobSubmitRequest("EMAIL", payload, "key-002", 3, 0);
        JobResponse response2 = jobService.submitJob(request2);

        assertNotEquals(response1.id(), response2.id(),
            "Different idempotency keys should create different jobs");
        verify(queueService, times(2)).enqueue(anyString(), anyLong());
    }

    @Test
    void shouldCreateNewJobWhenNoIdempotencyKey() {
        Map<String, Object> payload = Map.of("task", "send-email", "durationMs", 100);

        JobSubmitRequest request1 = new JobSubmitRequest("EMAIL", payload, null, 3, 0);
        JobResponse response1 = jobService.submitJob(request1);

        JobSubmitRequest request2 = new JobSubmitRequest("EMAIL", payload, null, 3, 0);
        JobResponse response2 = jobService.submitJob(request2);

        assertNotEquals(response1.id(), response2.id(),
            "Jobs without idempotency key should always create new jobs");
    }

    @Test
    void shouldPreserveOriginalJobStatusOnDuplicate() {
        String idempotencyKey = "status-preserve-key";
        Map<String, Object> payload = Map.of("task", "test", "durationMs", 100);

        JobSubmitRequest request = new JobSubmitRequest("TEST", payload, idempotencyKey, 3, 0);
        JobResponse originalResponse = jobService.submitJob(request);

        Job job = jobRepository.findById(originalResponse.id()).orElseThrow();
        job.setStatus(JobStatus.RUNNING);
        jobRepository.save(job);

        JobResponse duplicateResponse = jobService.submitJob(request);
        assertEquals(JobStatus.RUNNING, duplicateResponse.status(),
            "Duplicate should return current status of existing job");
    }
}
