package dev.tylercash.event.lifecycle;

import jakarta.annotation.PostConstruct;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
@RequiredArgsConstructor
public class EventBusConfig {

    private final List<DurableEventListener<?>> listeners;

    @Bean
    public AsyncTaskExecutor eventBusExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(4);
        ex.setMaxPoolSize(16);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("event-bus-");
        ex.initialize();
        return ex;
    }

    @PostConstruct
    void validateListenerNamesAreUnique() {
        Set<String> seen = new HashSet<>();
        for (DurableEventListener<?> l : listeners) {
            if (!seen.add(l.name())) {
                throw new IllegalStateException("Duplicate DurableEventListener name: " + l.name());
            }
        }
    }
}
