package dev.tylercash.event.global;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * Guards the logback -> OTel SDK bridge. The OTel Spring Boot starter used to call
 * {@link OpenTelemetryAppender#install(OpenTelemetry)} at {@code ApplicationReadyEvent}; dropping
 * the starter (commit 8db6113) silently orphaned the appender declared in {@code logback-spring.xml}
 * — it has no SDK to ship to and drops every record, so prod logs vanished from Grafana while
 * traces/metrics (separate paths) kept flowing. This test fails if that install wiring is missing.
 */
class ObservabilityConfigurationOtelLoggingTest {

    private static final String TEST_LOGGER = "otel-appender-bridge-test";

    private final List<LogRecordData> exported = new ArrayList<>();
    private OpenTelemetryAppender appender;
    private OpenTelemetrySdk sdk;

    @AfterEach
    void tearDown() {
        if (appender != null) {
            ((Logger) LoggerFactory.getLogger(TEST_LOGGER)).detachAppender(appender);
            appender.stop();
        }
        // Un-bridge any appender instances so the global logback context doesn't leak into other
        // tests sharing this JVM.
        OpenTelemetryAppender.install(OpenTelemetry.noop());
        if (sdk != null) {
            sdk.close();
        }
    }

    @Test
    void onApplicationReady_bridgesLogbackAppenderToSdk_soLogsReachTheExporter() {
        // given: an SDK whose logger provider exports synchronously to an in-memory list
        sdk = OpenTelemetrySdk.builder()
                .setLoggerProvider(SdkLoggerProvider.builder()
                        .addLogRecordProcessor(SimpleLogRecordProcessor.create(inMemoryExporter()))
                        .build())
                .build();

        // and: a logback OpenTelemetryAppender attached to a dedicated logger but NOT yet bridged
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        appender = new OpenTelemetryAppender();
        appender.setContext(loggerContext);
        appender.start();
        Logger testLogger = (Logger) LoggerFactory.getLogger(TEST_LOGGER);
        testLogger.addAppender(appender);
        testLogger.setAdditive(false);

        // when: the application-ready listener fires
        ApplicationListener<ApplicationReadyEvent> installer =
                new ObservabilityConfiguration().otelLogbackAppenderInstaller(sdk);
        installer.onApplicationEvent(null);

        // and: a log line is emitted through the bridged appender
        testLogger.info("bridge-marker-42");
        sdk.getSdkLoggerProvider().forceFlush().join(5, TimeUnit.SECONDS);

        // then: it reaches the SDK exporter, proving the appender is wired to the SDK
        assertThat(exported)
                .anyMatch(record -> record.getBodyValue() != null
                        && record.getBodyValue().asString().contains("bridge-marker-42"));
    }

    private LogRecordExporter inMemoryExporter() {
        return new LogRecordExporter() {
            @Override
            public CompletableResultCode export(Collection<LogRecordData> logs) {
                exported.addAll(logs);
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
    }
}
