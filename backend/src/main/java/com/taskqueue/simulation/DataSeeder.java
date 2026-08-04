package com.taskqueue.simulation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskqueue.domain.Job;
import com.taskqueue.domain.JobStatus;
import com.taskqueue.domain.Tenant;
import com.taskqueue.repository.JobRepository;
import com.taskqueue.repository.TenantRepository;
import com.taskqueue.service.QueueService;
import com.taskqueue.service.TenantService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder {

    private final TenantRepository tenantRepository;
    private final TenantService tenantService;
    private final JobRepository jobRepository;
    private final QueueService queueService;
    private final ObjectMapper objectMapper;

    @PostConstruct
    @Transactional
    public void seed() {
        seedTenants();
        rehydrateQueue();
    }

    private void seedTenants() {
        try {
            List<Map<String, Object>> tenants = loadTenantConfig();

            for (Map<String, Object> config : tenants) {
                String id = (String) config.get("id");
                if (tenantRepository.existsById(id)) {
                    log.debug("Tenant {} already exists, skipping", id);
                    continue;
                }

                String name = (String) config.get("name");
                String apiKey = (String) config.get("apiKey");
                int rateLimitPerMinute = config.containsKey("rateLimitPerMinute")
                    ? ((Number) config.get("rateLimitPerMinute")).intValue() : 100;
                int maxConcurrentJobs = config.containsKey("maxConcurrentJobs")
                    ? ((Number) config.get("maxConcurrentJobs")).intValue() : 10;
                int maxRetriesDefault = config.containsKey("maxRetriesDefault")
                    ? ((Number) config.get("maxRetriesDefault")).intValue() : 3;
                boolean requireIdempotencyKey = config.containsKey("requireIdempotencyKey")
                    && (Boolean) config.get("requireIdempotencyKey");

                Tenant created = tenantService.createTenant(id, name, rateLimitPerMinute,
                    maxConcurrentJobs, maxRetriesDefault, requireIdempotencyKey, apiKey);
                log.info("Seeded tenant: {} (API key: {})", id, created.getApiKey());
            }
        } catch (Exception e) {
            log.error("Failed to seed tenants: {}", e.getMessage(), e);
        }
    }

    private void rehydrateQueue() {
        try {
            List<Job> pendingJobs = jobRepository.findByStatus(JobStatus.PENDING);
            if (pendingJobs.isEmpty()) {
                log.info("No PENDING jobs to rehydrate into queue");
                return;
            }

            int rehydrated = 0;
            for (Job job : pendingJobs) {
                String jobId = job.getId().toString();
                long executeAt = job.getScheduledAt() != null
                    ? job.getScheduledAt().toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
                    : Instant.now().toEpochMilli();

                // Check not already in queue — just enqueue (ZSET deduplicates by member)
                queueService.enqueue(jobId, executeAt);
                rehydrated++;
            }

            log.info("Rehydrated {} PENDING jobs into Redis queue", rehydrated);
        } catch (Exception e) {
            log.error("Failed to rehydrate queue: {}", e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> loadTenantConfig() throws Exception {
        // Try external config first
        File externalFile = new File("/config/tenants.json");
        if (externalFile.exists()) {
            log.info("Loading tenant config from /config/tenants.json");
            return objectMapper.readValue(externalFile, new TypeReference<>() {});
        }

        // Fallback to classpath
        log.info("Loading tenant config from classpath");
        InputStream is = getClass().getResourceAsStream("/config/tenants.json");
        if (is == null) {
            throw new RuntimeException("tenants.json not found on classpath or /config/");
        }
        return objectMapper.readValue(is, new TypeReference<>() {});
    }
}
