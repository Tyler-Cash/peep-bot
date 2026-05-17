package dev.tylercash.event.discord.listener;

import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Overrides the production {@code discordListenerExecutor} with a caller-runs executor so
 * {@code @SpringBootTest} integration tests can assert side effects synchronously without racing
 * the real thread pool.
 */
@TestConfiguration
public class SynchronousDiscordListenerExecutorTestConfig {

    @Bean(name = "discordListenerExecutor", destroyMethod = "shutdown")
    @Primary
    public ExecutorService discordListenerExecutor() {
        return new AbstractExecutorService() {
            private volatile boolean shutdown = false;

            @Override
            public void execute(Runnable command) {
                command.run();
            }

            @Override
            public void shutdown() {
                shutdown = true;
            }

            @Override
            public java.util.List<Runnable> shutdownNow() {
                shutdown = true;
                return java.util.Collections.emptyList();
            }

            @Override
            public boolean isShutdown() {
                return shutdown;
            }

            @Override
            public boolean isTerminated() {
                return shutdown;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }
        };
    }
}
