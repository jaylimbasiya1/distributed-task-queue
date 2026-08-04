package com.taskqueue.simulation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskqueue.domain.Tenant;
import com.taskqueue.dto.JobSubmitRequest;
import com.taskqueue.repository.TenantRepository;
import com.taskqueue.security.TenantContext;
import com.taskqueue.service.JobService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class SimulationRunner {

    private final JobService jobService;
    private final TenantRepository tenantRepository;
    private final ObjectMapper objectMapper;
    private final Random random = new Random();

    @PostConstruct
    public void init() {
        try {
            Map<String, Object> config = loadSimulationConfig();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tenantConfigs =
                (List<Map<String, Object>>) config.get("tenants");

            if (tenantConfigs == null || tenantConfigs.isEmpty()) {
                log.info("No simulation tenants configured, skipping simulation");
                return;
            }

            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(tenantConfigs.size());
            scheduler.setThreadNamePrefix("simulation-");
            scheduler.initialize();

            for (Map<String, Object> tenantConfig : tenantConfigs) {
                String tenantId = (String) tenantConfig.get("tenantId");
                int jobsPerMinute = tenantConfig.containsKey("jobsPerMinute")
                    ? ((Number) tenantConfig.get("jobsPerMinute")).intValue() : 10;
                double failureRate = tenantConfig.containsKey("failureRate")
                    ? ((Number) tenantConfig.get("failureRate")).doubleValue() : 0.1;
                @SuppressWarnings("unchecked")
                List<String> jobTypes = (List<String>) tenantConfig.get("jobTypes");
                int minDurationMs = tenantConfig.containsKey("minDurationMs")
                    ? ((Number) tenantConfig.get("minDurationMs")).intValue() : 500;
                int maxDurationMs = tenantConfig.containsKey("maxDurationMs")
                    ? ((Number) tenantConfig.get("maxDurationMs")).intValue() : 2000;

                long intervalMs = 60_000L / Math.max(1, jobsPerMinute);

                AtomicInteger counter = new AtomicInteger(0);

                scheduler.scheduleAtFixedRate(() -> {
                    try {
                        Optional<Tenant> tenantOpt = tenantRepository.findById(tenantId);
                        if (tenantOpt.isEmpty()) {
                            log.debug("Tenant {} not found, skipping simulation tick", tenantId);
                            return;
                        }

                        Tenant tenant = tenantOpt.get();
                        TenantContext.set(tenant);

                        try {
                            String type = jobTypes != null && !jobTypes.isEmpty()
                                ? jobTypes.get(random.nextInt(jobTypes.size()))
                                : "GENERIC";

                            long duration = minDurationMs + random.nextInt(maxDurationMs - minDurationMs + 1);

                            Map<String, Object> payload = Map.of(
                                "durationMs", duration,
                                "failureRate", failureRate,
                                "simulationIndex", counter.incrementAndGet()
                            );

                            JobSubmitRequest req = new JobSubmitRequest(
                                type, payload, null, 3, 0
                            );

                            jobService.submitJob(req);
                            log.debug("Simulation submitted job for tenant {}", tenantId);
                        } finally {
                            TenantContext.clear();
                        }
                    } catch (Exception e) {
                        log.error("Simulation error for tenant {}: {}", tenantId, e.getMessage(), e);
                        TenantContext.clear();
                    }
                }, Duration.ofMillis(intervalMs));

                log.info("Simulation started for tenant {} at {} jobs/min (interval: {}ms)",
                    tenantId, jobsPerMinute, intervalMs);
            }
        } catch (Exception e) {
            log.warn("Could not start simulation runner: {}", e.getMessage());
        }
    }

    private Map<String, Object> loadSimulationConfig() throws Exception {
        File externalFile = new File("/config/simulation.json");
        if (externalFile.exists()) {
            log.info("Loading simulation config from /config/simulation.json");
            return objectMapper.readValue(externalFile, new TypeReference<>() {});
        }

        log.info("Loading simulation config from classpath");
        InputStream is = getClass().getResourceAsStream("/config/simulation.json");
        if (is == null) {
            throw new RuntimeException("simulation.json not found on classpath or /config/");
        }
        return objectMapper.readValue(is, new TypeReference<>() {});
    }
}
