package com.taskqueue.domain;

public enum JobStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    DLQ,
    CANCELLED
}
