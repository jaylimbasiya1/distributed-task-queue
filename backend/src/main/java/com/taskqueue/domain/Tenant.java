package com.taskqueue.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "tenants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tenant {

    @Id
    @Column(name = "id", length = 100)
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "api_key", nullable = false, unique = true)
    private String apiKey;

    @Column(name = "rate_limit_per_minute", nullable = false)
    private int rateLimitPerMinute = 100;

    @Column(name = "max_concurrent_jobs", nullable = false)
    private int maxConcurrentJobs = 10;

    @Column(name = "max_retries_default", nullable = false)
    private int maxRetriesDefault = 3;

    @Column(name = "require_idempotency_key", nullable = false)
    private boolean requireIdempotencyKey = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
