package com.taskqueue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record JobSubmitRequest(
    @NotBlank String type,
    @NotNull Map<String, Object> payload,
    String idempotencyKey,
    int maxRetries,
    long delayMs
) {}
