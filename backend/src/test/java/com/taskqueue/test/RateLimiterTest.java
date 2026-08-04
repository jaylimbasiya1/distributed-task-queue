package com.taskqueue.test;

import com.taskqueue.config.AppProperties;
import com.taskqueue.service.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RateLimiterTest {

    private RedisTemplate<String, String> redisTemplate;
    private ValueOperations<String, String> valueOps;
    private AppProperties appProperties;
    private RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(RedisTemplate.class);
        valueOps = Mockito.mock(ValueOperations.class);
        appProperties = new AppProperties();
        appProperties.getRateLimit().setWindowSeconds(60);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        rateLimiterService = new RateLimiterService(redisTemplate, appProperties);
    }

    @Test
    void shouldAllowRequestWhenUnderLimit() {
        when(valueOps.increment(anyString())).thenReturn(1L);

        boolean allowed = rateLimiterService.isAllowed("tenant-1", 100);

        assertTrue(allowed, "Request should be allowed when under the rate limit");
    }

    @Test
    void shouldBlockRequestWhenOverLimit() {
        when(valueOps.increment(anyString())).thenReturn(101L);

        boolean allowed = rateLimiterService.isAllowed("tenant-1", 100);

        assertFalse(allowed, "Request should be blocked when over the rate limit");
    }

    @Test
    void shouldAllowAtExactLimit() {
        when(valueOps.increment(anyString())).thenReturn(100L);

        boolean allowed = rateLimiterService.isAllowed("tenant-1", 100);

        assertTrue(allowed, "Request at exact limit should be allowed");
    }

    @Test
    void shouldSetExpiryOnFirstIncrement() {
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        rateLimiterService.isAllowed("tenant-1", 100);

        verify(redisTemplate).expire(anyString(), eq(120L), eq(TimeUnit.SECONDS));
    }

    @Test
    void shouldNotSetExpiryOnSubsequentIncrements() {
        when(valueOps.increment(anyString())).thenReturn(5L);

        rateLimiterService.isAllowed("tenant-1", 100);

        verify(redisTemplate, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void shouldFailOpenOnRedisError() {
        when(valueOps.increment(anyString())).thenThrow(new RuntimeException("Redis unavailable"));

        boolean allowed = rateLimiterService.isAllowed("tenant-1", 100);

        assertTrue(allowed, "Should fail open when Redis is unavailable");
    }
}
