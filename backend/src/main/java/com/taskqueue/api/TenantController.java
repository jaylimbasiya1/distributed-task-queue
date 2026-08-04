package com.taskqueue.api;

import com.taskqueue.domain.Tenant;
import com.taskqueue.dto.TenantStatsResponse;
import com.taskqueue.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
@Tag(name = "Tenants", description = "Tenant management endpoints")
public class TenantController {

    private final TenantService tenantService;

    @PostMapping
    @Operation(summary = "Create a new tenant")
    public ResponseEntity<Tenant> createTenant(@RequestBody Map<String, Object> body) {
        String id = (String) body.get("id");
        String name = (String) body.get("name");
        int rateLimitPerMinute = body.containsKey("rateLimitPerMinute")
            ? ((Number) body.get("rateLimitPerMinute")).intValue() : 100;
        int maxConcurrentJobs = body.containsKey("maxConcurrentJobs")
            ? ((Number) body.get("maxConcurrentJobs")).intValue() : 10;
        int maxRetriesDefault = body.containsKey("maxRetriesDefault")
            ? ((Number) body.get("maxRetriesDefault")).intValue() : 3;
        boolean requireIdempotencyKey = body.containsKey("requireIdempotencyKey")
            && (Boolean) body.get("requireIdempotencyKey");

        Tenant tenant = tenantService.createTenant(id, name, rateLimitPerMinute,
            maxConcurrentJobs, maxRetriesDefault, requireIdempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(tenant);
    }

    @GetMapping("/{id}/stats")
    @Operation(summary = "Get job statistics for a tenant")
    public ResponseEntity<TenantStatsResponse> getStats(@PathVariable String id) {
        return ResponseEntity.ok(tenantService.getStats(id));
    }
}
