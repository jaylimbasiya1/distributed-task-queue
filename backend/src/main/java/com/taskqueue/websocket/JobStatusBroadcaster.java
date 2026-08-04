package com.taskqueue.websocket;

import com.taskqueue.domain.Job;
import com.taskqueue.dto.JobResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobStatusBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    public void broadcast(Job job) {
        JobResponse response = JobResponse.from(job);
        try {
            // Broadcast to all subscribers
            messagingTemplate.convertAndSend("/topic/jobs", response);
            // Broadcast to tenant-specific topic
            messagingTemplate.convertAndSend("/topic/jobs/" + job.getTenantId(), response);
            log.debug("Broadcasted status change for job {} ({}) to tenant {}",
                job.getId(), job.getStatus(), job.getTenantId());
        } catch (Exception e) {
            log.error("Failed to broadcast job status for job {}: {}", job.getId(), e.getMessage(), e);
        }
    }
}
