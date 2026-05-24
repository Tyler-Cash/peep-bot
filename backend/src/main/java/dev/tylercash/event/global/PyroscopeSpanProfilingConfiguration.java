package dev.tylercash.event.global;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.otel.pyroscope.PyroscopeOtelConfiguration;
import io.otel.pyroscope.PyroscopeOtelSpanProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Links traces to profiles at the span level. {@link PyroscopeOtelSpanProcessor} tags the running
 * Pyroscope profiler (the {@code -javaagent} attached in the portainer peepbot stack) with the
 * active span id and stamps a {@code pyroscope.profile.id} attribute on each span, so Grafana can
 * show the flame graph for an individual span (Tempo {@code tracesToProfiles} with a span-scoped
 * query).
 *
 * <p>Spring Boot adds {@link SpanProcessor} beans to the OTel {@code SdkTracerProvider} that backs
 * micrometer-tracing. The processor talks to the agent via the shared {@code io.pyroscope.*}
 * classes (the agent is on the system class loader; app code resolves to it parent-first), so it
 * uses the same profiler that produces the profiles.
 *
 * <p>Defensive on purpose: profiling is an observability add-on and must never take down the app.
 * If the agent/classes aren't present the bean is a no-op, and per-span calls are wrapped so a
 * runtime error in the profiler bridge can't break span recording.
 */
@Slf4j
@Configuration
public class PyroscopeSpanProfilingConfiguration {

    @Bean
    SpanProcessor pyroscopeSpanProcessor() {
        try {
            PyroscopeOtelConfiguration config = new PyroscopeOtelConfiguration.Builder()
                    .setRootSpanOnly(false) // per-span: tag every span, not just the request root
                    .setAddSpanName(true)
                    .build();
            SpanProcessor processor = new SafeSpanProcessor(new PyroscopeOtelSpanProcessor(config));
            logLabelsApiOrigin();
            return processor;
        } catch (Throwable t) {
            // No Pyroscope agent on the classpath (e.g. local/dev runs without the -javaagent),
            // or an incompatible version — disable span profiling rather than fail startup.
            log.warn("Pyroscope span profiling disabled: {}", t.toString());
            return SpanProcessor.composite();
        }
    }

    /**
     * Logs where the {@code io.pyroscope} labels API resolved from. Healthy: a {@code null} code
     * source or {@code /agent/pyroscope.jar} (the {@code -javaagent}). Broken: a path into the app's
     * {@code BOOT-INF/lib/agent-*.jar}, which means a bundled copy is shadowing the agent's registry.
     */
    private static void logLabelsApiOrigin() {
        var source =
                io.pyroscope.labels.v2.Pyroscope.class.getProtectionDomain().getCodeSource();
        log.info(
                "Pyroscope span profiling enabled; io.pyroscope labels API loaded from {}",
                source == null ? "bootstrap/agent class loader (no code source)" : source.getLocation());
    }

    /**
     * Wraps the Pyroscope span processor so any failure in its per-span profiler calls is logged
     * once and swallowed — span recording (and thus tracing) must continue regardless.
     */
    private static final class SafeSpanProcessor implements SpanProcessor {
        private final SpanProcessor delegate;
        private volatile boolean degraded = false;

        SafeSpanProcessor(SpanProcessor delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
            if (degraded) return;
            try {
                delegate.onStart(parentContext, span);
            } catch (Throwable t) {
                degrade(t);
            }
        }

        @Override
        public boolean isStartRequired() {
            return true;
        }

        @Override
        public void onEnd(ReadableSpan span) {
            if (degraded) return;
            try {
                delegate.onEnd(span);
            } catch (Throwable t) {
                degrade(t);
            }
        }

        @Override
        public boolean isEndRequired() {
            return true;
        }

        @Override
        public CompletableResultCode shutdown() {
            return delegate.shutdown();
        }

        @Override
        public CompletableResultCode forceFlush() {
            return delegate.forceFlush();
        }

        private void degrade(Throwable t) {
            degraded = true;
            log.warn("Pyroscope span profiling degraded after a runtime error; disabling it", t);
        }
    }
}
