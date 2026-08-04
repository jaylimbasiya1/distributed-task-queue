package com.taskqueue.worker;

import com.taskqueue.config.AppProperties;
import com.taskqueue.service.QueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerPoolManager {

    private final WorkerPool workerPool;
    private final QueueService queueService;
    private final AppProperties appProperties;

    private long lastScaleDownTime = System.currentTimeMillis();

    @Scheduled(fixedDelay = 10_000)
    public void adjustPoolSize() {
        long depth = queueService.depth();
        int currentSize = workerPool.getCurrentPoolSize();
        int maxPoolSize = appProperties.getWorker().getMaxPoolSize();
        int minPoolSize = appProperties.getWorker().getMinPoolSize();
        int scaleUpThreshold = appProperties.getAutoscaling().getScaleUpThreshold();
        int scaleStep = appProperties.getAutoscaling().getScaleStep();
        int scaleDownIdleSeconds = appProperties.getAutoscaling().getScaleDownIdleSeconds();

        if (depth >= scaleUpThreshold && currentSize < maxPoolSize) {
            int newSize = Math.min(currentSize + scaleStep, maxPoolSize);
            log.info("Autoscaling UP: queue depth={}, pool size {} -> {}", depth, currentSize, newSize);
            workerPool.setPoolSize(newSize);
            lastScaleDownTime = System.currentTimeMillis();
        } else if (depth == 0 && currentSize > minPoolSize) {
            long idleMs = System.currentTimeMillis() - lastScaleDownTime;
            if (idleMs >= scaleDownIdleSeconds * 1000L) {
                int newSize = Math.max(currentSize - scaleStep, minPoolSize);
                log.info("Autoscaling DOWN: queue depth={}, pool size {} -> {}", depth, currentSize, newSize);
                workerPool.setPoolSize(newSize);
                lastScaleDownTime = System.currentTimeMillis();
            }
        } else {
            lastScaleDownTime = System.currentTimeMillis();
        }
    }
}
