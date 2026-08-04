package com.taskqueue.service;

import com.taskqueue.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private final RedisTemplate<String, String> redisTemplate;
    private final AppProperties appProperties;

    /**
     * Sliding window rate limiter.
     * Key: ratelimit:{tenantId}:{windowStart}
     * Returns true if request is allowed, false if rate limited.
     */
    public boolean isAllowed(String tenantId, int limitPerMinute) {
        int windowSeconds = appProperties.getRateLimit().getWindowSeconds();
        long now = Instant.now().getEpochSecond();
        long windowStart = now / windowSeconds;

        String key = "ratelimit:" + tenantId + ":" + windowStart;

        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                log.warn("Failed to increment rate limit counter for tenant {}", tenantId);
                return true; // fail open
            }

            if (count == 1) {
                // Set expiry on first increment
                redisTemplate.expire(key, windowSeconds * 2L, TimeUnit.SECONDS);
            }

            boolean allowed = count <= limitPerMinute;
            if (!allowed) {
                log.warn("Rate limit exceeded for tenant {} ({}/{})", tenantId, count, limitPerMinute);
            }
            return allowed;
        } catch (Exception e) {
            log.error("Rate limiter error for tenant {}: {}", tenantId, e.getMessage());
            return true; // fail open on Redis error
        }
    }
}
