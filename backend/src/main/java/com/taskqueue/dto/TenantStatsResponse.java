package com.taskqueue.dto;

public record TenantStatsResponse(
    String tenantId,
    long pending,
    long running,
    long completed,
    long failed,
    long dlq,
    long cancelled
) {}
