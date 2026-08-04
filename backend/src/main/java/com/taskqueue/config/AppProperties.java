package com.taskqueue.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "taskqueue")
public class AppProperties {

    private Worker worker = new Worker();
    private Retry retry = new Retry();
    private Autoscaling autoscaling = new Autoscaling();
    private RateLimit rateLimit = new RateLimit();

    @Data
    public static class Worker {
        private int minPoolSize = 5;
        private int maxPoolSize = 50;
        private long pollIntervalMs = 500;
        private int leaseTtlSeconds = 30;
        private int leaseReaperIntervalSeconds = 15;
    }

    @Data
    public static class Retry {
        private long baseDelayMs = 2000;
        private long maxDelayMs = 60000;
    }

    @Data
    public static class Autoscaling {
        private int scaleUpThreshold = 50;
        private int scaleDownIdleSeconds = 60;
        private int scaleStep = 2;
    }

    @Data
    public static class RateLimit {
        private int windowSeconds = 60;
    }
}
