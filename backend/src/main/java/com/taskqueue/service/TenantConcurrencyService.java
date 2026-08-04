package com.taskqueue.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Tracks per-tenant in-flight job counts using a Redis counter.
 *
 * Why Redis instead of SELECT COUNT(*):
 *   A DB count-then-check is a check-then-act race — two workers reading 0 simultaneously
 *   will both proceed, silently breaching the tenant's maxConcurrentJobs limit.
 *   Redis INCR is atomic: only the worker whose INCR returns a value <= the limit proceeds;
 *   any worker that pushes the counter over the limit immediately rolls it back with DECR
 *   and re-queues the job.
 *
 * Crash recovery:
 *   If a worker crashes without decrementing (lease expires, LeaseReaper re-queues),
 *   LeaseReaper calls releaseSlot() to decrement. The clamp-to-0 guard in releaseSlot()
 *   prevents the counter from going negative if the crash happened before INCR.
 *
 * Restart recovery:
 *   On startup, WorkerPool seeds each tenant's counter from the DB's RUNNING row count
 *   so jobs that survived a restart are accounted for before the LeaseReaper fires.
 *
 * Fail-open:
 *   If Redis is unavailable, tryAcquireSlot() returns true so jobs still execute.
 *   This matches the RateLimiterService policy: availability over strict enforcement.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantConcurrencyService {

    private static final String KEY_PREFIX = "concurrency:";

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * Atomically claims a concurrency slot for a tenant.
     * Returns true if the slot was acquired (caller MUST call releaseSlot when done).
     * Returns false if the tenant is already at its limit (counter is unchanged).
     */
    public boolean tryAcquireSlot(String tenantId, int maxConcurrentJobs) {
        String key = KEY_PREFIX + tenantId;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count <= maxConcurrentJobs) {
                log.debug("Concurrency slot acquired for tenant {} ({}/{})", tenantId, count, maxConcurrentJobs);
                return true;
            }
            // Over limit — undo the increment so the counter stays accurate
            redisTemplate.opsForValue().decrement(key);
            log.debug("Tenant {} at concurrency limit ({}/{}), slot not acquired", tenantId, count, maxConcurrentJobs);
            return false;
        } catch (Exception e) {
            log.error("Redis error checking concurrency for tenant {} — failing open: {}", tenantId, e.getMessage());
            return true;
        }
    }

    /**
     * Releases a previously acquired concurrency slot.
     * Clamps to 0 to absorb any counter skew from crash-recovery decrements.
     */
    public void releaseSlot(String tenantId) {
        String key = KEY_PREFIX + tenantId;
        try {
            Long current = redisTemplate.opsForValue().decrement(key);
            if (current != null && current < 0) {
                redisTemplate.opsForValue().set(key, "0");
                log.warn("Concurrency counter for tenant {} went negative — clamped to 0 (crash-recovery skew)", tenantId);
            } else {
                log.debug("Released concurrency slot for tenant {}, active: {}", tenantId, current);
            }
        } catch (Exception e) {
            log.error("Redis error releasing concurrency slot for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    /**
     * Seeds the counter for a tenant to a known value.
     * Used at startup to account for RUNNING jobs that survived an app restart.
     */
    public void seedCount(String tenantId, long count) {
        String key = KEY_PREFIX + tenantId;
        try {
            redisTemplate.opsForValue().set(key, String.valueOf(count));
            log.info("Seeded concurrency counter for tenant {} = {}", tenantId, count);
        } catch (Exception e) {
            log.error("Redis error seeding concurrency count for tenant {}: {}", tenantId, e.getMessage());
        }
    }
}
