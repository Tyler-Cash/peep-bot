package dev.tylercash.event.discord.listener;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

@Configuration
public class DiscordListenerExecutorConfig {

    @Bean(name = "discordListenerExecutor", destroyMethod = "shutdown")
    public ExecutorService discordListenerExecutor(MeterRegistry meterRegistry) {
        ThreadPoolExecutor delegate = new ThreadPoolExecutor(
                4,
                16,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(512),
                new CustomizableThreadFactory("discord-listener-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        delegate.allowCoreThreadTimeOut(true);
        // Bind pool/queue gauges (executor.active, executor.pool.size, executor.queued,
        // executor.completed) directly onto the underlying ThreadPoolExecutor.
        // Spring Boot's executor auto-binding only handles ThreadPoolTaskExecutor; the
        // ContextExecutorService.wrap() below would otherwise hide the pool from
        // Micrometer entirely and leave the Discord Listener Health panel blank.
        ExecutorServiceMetrics.monitor(meterRegistry, delegate, "discordListenerExecutor", Tags.empty());
        // Capture the caller's context (active Span, MDC) at submit time so the offloaded
        // work runs as a child of the JDA-thread span and logs carry trace_id/span_id.
        ContextSnapshotFactory snapshots = ContextSnapshotFactory.builder().build();
        return ContextExecutorService.wrap(delegate, snapshots::captureAll);
    }
}
