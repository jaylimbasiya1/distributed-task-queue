package com.taskqueue.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private static final String QUEUE_KEY = "task-queue";

    private final RedisTemplate<String, String> redisTemplate;

    private static final DefaultRedisScript<String> POP_SCRIPT;

    static {
        POP_SCRIPT = new DefaultRedisScript<>();
        POP_SCRIPT.setResultType(String.class);
        POP_SCRIPT.setScriptText(
            "local items = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, 1)\n" +
            "if #items == 0 then return nil end\n" +
            "redis.call('ZREM', KEYS[1], items[1])\n" +
            "return items[1]"
        );
    }

    /**
     * Push job to Redis ZSET with score = executeAtEpochMs.
     */
    public void enqueue(String jobId, long executeAtEpochMs) {
        try {
            redisTemplate.opsForZSet().add(QUEUE_KEY, jobId, executeAtEpochMs);
            log.debug("Enqueued job {} with score {}", jobId, executeAtEpochMs);
        } catch (Exception e) {
            log.error("Failed to enqueue job {}: {}", jobId, e.getMessage(), e);
            throw new RuntimeException("Failed to enqueue job: " + jobId, e);
        }
    }

    /**
     * Atomically pop the next due job from the queue using Lua script.
     * Returns jobId or null if queue is empty or no jobs are due yet.
     */
    public String poll() {
        try {
            long now = System.currentTimeMillis();
            List<String> keys = Collections.singletonList(QUEUE_KEY);
            String jobId = redisTemplate.execute(POP_SCRIPT, keys, String.valueOf(now));
            if (jobId != null) {
                log.debug("Polled job {} from queue", jobId);
            }
            return jobId;
        } catch (Exception e) {
            log.error("Failed to poll queue: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Returns the number of items in the queue.
     */
    public long depth() {
        try {
            Long size = redisTemplate.opsForZSet().size(QUEUE_KEY);
            return size != null ? size : 0L;
        } catch (Exception e) {
            log.error("Failed to get queue depth: {}", e.getMessage(), e);
            return 0L;
        }
    }

    /**
     * Remove a specific job from the queue (for cancellation).
     */
    public void remove(String jobId) {
        try {
            redisTemplate.opsForZSet().remove(QUEUE_KEY, jobId);
            log.debug("Removed job {} from queue", jobId);
        } catch (Exception e) {
            log.error("Failed to remove job {} from queue: {}", jobId, e.getMessage(), e);
        }
    }
}
