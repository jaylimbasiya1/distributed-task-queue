package com.taskqueue.api;

import com.taskqueue.domain.DLQEntry;
import com.taskqueue.domain.Job;
import com.taskqueue.dto.JobResponse;
import com.taskqueue.service.DLQService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/dlq")
@RequiredArgsConstructor
@Tag(name = "DLQ", description = "Dead Letter Queue management endpoints")
public class DLQController {

    private final DLQService dlqService;

    @GetMapping
    @Operation(summary = "List all DLQ entries for the current tenant")
    public ResponseEntity<List<DLQEntry>> listDLQ() {
        return ResponseEntity.ok(dlqService.listDLQ());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Purge a DLQ entry")
    public ResponseEntity<Void> purgeDLQ(@PathVariable UUID id) {
        dlqService.purgeDLQ(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/retry")
    @Operation(summary = "Retry a DLQ entry as a new job")
    public ResponseEntity<JobResponse> retryFromDLQ(@PathVariable UUID id) {
        Job job = dlqService.retryFromDLQ(id);
        return ResponseEntity.ok(JobResponse.from(job));
    }
}
