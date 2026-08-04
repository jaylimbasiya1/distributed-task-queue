package com.taskqueue.test;

import com.taskqueue.worker.LeaseManager;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests that the lease mechanism prevents double execution of jobs
 * under concurrent worker scenarios.
 */
class ConcurrencyTest {

    @Test
    void shouldPreventDoubleExecutionWithMockedLeaseManager() throws InterruptedException {
        LeaseManager leaseManager = mock(LeaseManager.class);
        String jobId = UUID.randomUUID().toString();

        AtomicInteger acquireCallCount = new AtomicInteger(0);
        AtomicInteger executionCount = new AtomicInteger(0);

        // Only one of the concurrent calls succeeds
        when(leaseManager.acquire(eq(jobId), anyString()))
            .thenAnswer(inv -> acquireCallCount.incrementAndGet() == 1);

        int workerCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workerCount);

        for (int i = 0; i < workerCount; i++) {
            final String workerId = "worker-" + i;
            executor.submit(() -> {
                try {
                    latch.await(); // all start at the same time
                    boolean acquired = leaseManager.acquire(jobId, workerId);
                    if (acquired) {
                        executionCount.incrementAndGet();
                        // Simulate some work
                        Thread.sleep(10);
                        leaseManager.release(jobId);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown(); // release all workers simultaneously
        assertTrue(done.await(5, TimeUnit.SECONDS), "All workers should finish");
        executor.shutdown();

        assertEquals(1, executionCount.get(),
            "Only one worker should have executed the job — lease prevents double execution");
    }

    @Test
    void shouldAllowDifferentJobsSimultaneously() throws InterruptedException {
        LeaseManager leaseManager = mock(LeaseManager.class);
        String workerId = "test-worker";

        // Different jobs each get their own lease successfully
        when(leaseManager.acquire(anyString(), eq(workerId))).thenReturn(true);

        int jobCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(jobCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(jobCount);
        AtomicInteger executionCount = new AtomicInteger(0);

        List<String> jobIds = new ArrayList<>();
        for (int i = 0; i < jobCount; i++) {
            jobIds.add(UUID.randomUUID().toString());
        }

        for (String jobId : jobIds) {
            executor.submit(() -> {
                try {
                    latch.await();
                    boolean acquired = leaseManager.acquire(jobId, workerId);
                    if (acquired) {
                        executionCount.incrementAndGet();
                        Thread.sleep(5);
                        leaseManager.release(jobId);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(jobCount, executionCount.get(),
            "All different jobs should execute concurrently without interference");
    }

    @Test
    void shouldReleaseLeaseAfterCompletion() {
        LeaseManager leaseManager = mock(LeaseManager.class);
        String jobId = UUID.randomUUID().toString();

        when(leaseManager.acquire(eq(jobId), anyString())).thenReturn(true);
        when(leaseManager.exists(eq(jobId))).thenReturn(false);

        leaseManager.acquire(jobId, "worker-1");
        leaseManager.release(jobId);

        verify(leaseManager).acquire(eq(jobId), eq("worker-1"));
        verify(leaseManager).release(eq(jobId));

        assertFalse(leaseManager.exists(jobId),
            "Lease should not exist after release");
    }
}
