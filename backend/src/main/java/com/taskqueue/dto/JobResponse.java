package com.taskqueue.dto;

import com.taskqueue.domain.Job;
import com.taskqueue.domain.JobStatus;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record JobResponse(
    UUID id,
    String tenantId,
    String type,
    Map<String, Object> payload,
    JobStatus status,
    int attempt,
    int maxRetries,
    String idempotencyKey,
    LocalDateTime scheduledAt,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    String lockedBy,
    String lastError,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static JobResponse from(Job job) {
        return new JobResponse(
            job.getId(),
            job.getTenantId(),
            job.getType(),
            job.getPayload(),
            job.getStatus(),
            job.getAttempt(),
            job.getMaxRetries(),
            job.getIdempotencyKey(),
            job.getScheduledAt(),
            job.getStartedAt(),
            job.getCompletedAt(),
            job.getLockedBy(),
            job.getLastError(),
            job.getCreatedAt(),
            job.getUpdatedAt()
        );
    }
}
