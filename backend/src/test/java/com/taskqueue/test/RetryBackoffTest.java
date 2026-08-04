package com.taskqueue.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class RetryBackoffTest {

    private static final long BASE_DELAY_MS = 2000L;
    private static final long MAX_DELAY_MS = 60000L;

    /**
     * Calculates exponential backoff delay.
     * Formula: delay = baseDelay * 2^attemptNumber, capped at maxDelay.
     */
    private long calculateBackoff(int attemptNumber) {
        long delay = BASE_DELAY_MS * (1L << attemptNumber);
        return Math.min(delay, MAX_DELAY_MS);
    }

    @Test
    void shouldCalculateCorrectDelayForAttempt0() {
        // attempt 0: 2000 * 2^0 = 2000ms
        long delay = calculateBackoff(0);
        assertEquals(2000L, delay, "Attempt 0 should have 2s delay");
    }

    @Test
    void shouldCalculateCorrectDelayForAttempt1() {
        // attempt 1: 2000 * 2^1 = 4000ms
        long delay = calculateBackoff(1);
        assertEquals(4000L, delay, "Attempt 1 should have 4s delay");
    }

    @Test
    void shouldCalculateCorrectDelayForAttempt2() {
        // attempt 2: 2000 * 2^2 = 8000ms
        long delay = calculateBackoff(2);
        assertEquals(8000L, delay, "Attempt 2 should have 8s delay");
    }

    @Test
    void shouldCapAtMaxDelay() {
        // High attempt number should cap at MAX_DELAY_MS
        long delay = calculateBackoff(20);
        assertEquals(MAX_DELAY_MS, delay, "Delay should cap at maxDelay");
    }

    @ParameterizedTest
    @CsvSource({
        "0, 2000",
        "1, 4000",
        "2, 8000",
        "3, 16000",
        "4, 32000",
        "5, 60000"
    })
    void shouldFollowExponentialBackoffPattern(int attempt, long expectedDelay) {
        long delay = calculateBackoff(attempt);
        assertEquals(expectedDelay, delay,
            "Attempt " + attempt + " should have delay " + expectedDelay + "ms");
    }

    @Test
    void shouldBeStrictlyIncreasing() {
        long prev = 0;
        for (int i = 0; i < 6; i++) {
            long delay = calculateBackoff(i);
            assertTrue(delay > prev || delay == MAX_DELAY_MS,
                "Delay should be strictly increasing until cap");
            prev = delay;
        }
    }

    @Test
    void shouldNeverExceedMaxDelay() {
        for (int i = 0; i < 100; i++) {
            long delay = calculateBackoff(i);
            assertTrue(delay <= MAX_DELAY_MS, "Delay should never exceed maxDelay");
        }
    }
}
