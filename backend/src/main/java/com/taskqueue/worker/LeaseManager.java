package com.taskqueue.worker;

import com.taskqueue.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class LeaseManager {

    private static final String LEASE_KEY_PREFIX = "lease:";

    private final RedisTemplate<String, String> redisTemplate;
    private final AppProperties appProperties;

    /**
     * Acquire a lease for the given jobId.
     * Uses SET NX EX for atomic acquire-or-fail.
     */
    public boolean acquire(String jobId, String workerId) {
        String key = LEASE_KEY_PREFIX + jobId;
        int ttl = appProperties.getWorker().getLeaseTtlSeconds();
        try {
            Boolean result = redisTemplate.opsForValue()
                .setIfAbsent(key, workerId, ttl, TimeUnit.SECONDS);
            boolean acquired = Boolean.TRUE.equals(result);
            if (acquired) {
                log.debug("Acquired lease for job {} by worker {}", jobId, workerId);
            } else {
                log.debug("Failed to acquire lease for job {} - already held", jobId);
            }
            return acquired;
        } catch (Exception e) {
            log.error("Error acquiring lease for job {}: {}", jobId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Release the lease for the given jobId.
     */
    public void release(String jobId) {
        String key = LEASE_KEY_PREFIX + jobId;
        try {
            redisTemplate.delete(key);
            log.debug("Released lease for job {}", jobId);
        } catch (Exception e) {
            log.error("Error releasing lease for job {}: {}", jobId, e.getMessage(), e);
        }
    }

    /**
     * Check if a lease exists for the given jobId.
     */
    public boolean exists(String jobId) {
        String key = LEASE_KEY_PREFIX + jobId;
        try {
            Boolean exists = redisTemplate.hasKey(key);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.error("Error checking lease for job {}: {}", jobId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Renew the lease TTL only if this worker still holds it.
     * Uses GET + EXPIRE — safe because only the holder would call this.
     */
    public boolean renew(String jobId, String workerId, int ttlSeconds) {
        String key = LEASE_KEY_PREFIX + jobId;
        try {
            String currentHolder = redisTemplate.opsForValue().get(key);
            if (workerId.equals(currentHolder)) {
                redisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
                log.debug("Renewed lease for job {} by worker {}", jobId, workerId);
                return true;
            }
            log.warn("Cannot renew lease for job {} — currently held by '{}', not '{}'",
                jobId, currentHolder, workerId);
            return false;
        } catch (Exception e) {
            log.error("Error renewing lease for job {}: {}", jobId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Check whether this worker still holds the lease for the given jobId.
     * Used after job execution to detect lease expiry + requeue by LeaseReaper.
     */
    public boolean isHeldBy(String jobId, String workerId) {
        String key = LEASE_KEY_PREFIX + jobId;
        try {
            String currentHolder = redisTemplate.opsForValue().get(key);
            return workerId.equals(currentHolder);
        } catch (Exception e) {
            log.error("Error checking lease ownership for job {}: {}", jobId, e.getMessage(), e);
            return false;
        }
    }
}
