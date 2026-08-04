package com.taskqueue.service;

import com.taskqueue.domain.JobStatus;
import com.taskqueue.domain.Tenant;
import com.taskqueue.dto.TenantStatsResponse;
import com.taskqueue.repository.JobRepository;
import com.taskqueue.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantRepository tenantRepository;
    private final JobRepository jobRepository;

    @Transactional
    public Tenant createTenant(String id, String name, int rateLimitPerMinute,
                               int maxConcurrentJobs, int maxRetriesDefault,
                               boolean requireIdempotencyKey) {
        return createTenant(id, name, rateLimitPerMinute, maxConcurrentJobs, maxRetriesDefault,
            requireIdempotencyKey, null);
    }

    @Transactional
    public Tenant createTenant(String id, String name, int rateLimitPerMinute,
                               int maxConcurrentJobs, int maxRetriesDefault,
                               boolean requireIdempotencyKey, String providedApiKey) {
        if (tenantRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Tenant already exists: " + id);
        }

        String apiKey = (providedApiKey != null && !providedApiKey.isBlank())
            ? providedApiKey
            : UUID.randomUUID().toString();

        Tenant tenant = Tenant.builder()
            .id(id)
            .name(name)
            .apiKey(apiKey)
            .rateLimitPerMinute(rateLimitPerMinute)
            .maxConcurrentJobs(maxConcurrentJobs)
            .maxRetriesDefault(maxRetriesDefault)
            .requireIdempotencyKey(requireIdempotencyKey)
            .build();

        tenant = tenantRepository.save(tenant);
        log.info("Created tenant {} with API key {}", id, apiKey);
        return tenant;
    }

    @Transactional(readOnly = true)
    public TenantStatsResponse getStats(String tenantId) {
        if (!tenantRepository.existsById(tenantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId);
        }

        long pending = jobRepository.countByTenantIdAndStatus(tenantId, JobStatus.PENDING);
        long running = jobRepository.countByTenantIdAndStatus(tenantId, JobStatus.RUNNING);
        long completed = jobRepository.countByTenantIdAndStatus(tenantId, JobStatus.COMPLETED);
        long failed = jobRepository.countByTenantIdAndStatus(tenantId, JobStatus.FAILED);
        long dlq = jobRepository.countByTenantIdAndStatus(tenantId, JobStatus.DLQ);
        long cancelled = jobRepository.countByTenantIdAndStatus(tenantId, JobStatus.CANCELLED);

        return new TenantStatsResponse(tenantId, pending, running, completed, failed, dlq, cancelled);
    }
}
