package com.exe.morphia3d.service.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.ai-generation", name = "worker-enabled", havingValue = "true")
public class AiGenerationWorkerScheduler {
    private final AiGenerationWorker worker;

    public AiGenerationWorkerScheduler(AiGenerationWorker worker) { this.worker = worker; }

    @Scheduled(fixedDelayString = "${app.ai-generation.worker-interval-millis:1000}")
    public void processNext() { worker.processOne(); }
}
