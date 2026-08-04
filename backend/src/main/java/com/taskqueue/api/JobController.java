package com.taskqueue.api;

import com.taskqueue.domain.JobStatus;
import com.taskqueue.dto.JobResponse;
import com.taskqueue.dto.JobSubmitRequest;
import com.taskqueue.service.JobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
@Tag(name = "Jobs", description = "Job management endpoints")
public class JobController {

    private final JobService jobService;

    @PostMapping
    @Operation(summary = "Submit a new job")
    public ResponseEntity<JobResponse> submitJob(@Valid @RequestBody JobSubmitRequest request) {
        JobResponse response = jobService.submitJob(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a job by ID")
    public ResponseEntity<JobResponse> getJob(@PathVariable UUID id) {
        return ResponseEntity.ok(jobService.getJob(id));
    }

    @GetMapping
    @Operation(summary = "List jobs with optional status filter")
    public ResponseEntity<Page<JobResponse>> listJobs(
        @RequestParam(required = false) JobStatus status,
        @PageableDefault(size = 20) Pageable pageable
    ) {
        return ResponseEntity.ok(jobService.listJobs(status, pageable));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Cancel a PENDING job")
    public ResponseEntity<JobResponse> cancelJob(@PathVariable UUID id) {
        return ResponseEntity.ok(jobService.cancelJob(id));
    }

    @PostMapping("/{id}/retry")
    @Operation(summary = "Retry a failed job")
    public ResponseEntity<JobResponse> retryJob(@PathVariable UUID id) {
        return ResponseEntity.ok(jobService.retryJob(id));
    }
}
