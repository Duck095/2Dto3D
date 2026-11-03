package com.exe.morphia3d.config;

import com.exe.morphia3d.service.MediaLifecycleProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.time.Clock;

@Configuration
@EnableScheduling
public class MediaLifecycleConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock mediaLifecycleClock() {
        return Clock.systemUTC();
    }

    @Bean
    @Primary
    public TaskScheduler mediaLifecycleTaskScheduler(MediaLifecycleProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(properties.getHeartbeatPoolSize());
        scheduler.setThreadNamePrefix("media-lifecycle-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }
}
