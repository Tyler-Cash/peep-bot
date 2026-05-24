package dev.tylercash.event.global;

import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Guards the {@code opentelemetry-logback-appender-1.0} ↔ {@code opentelemetry-api} version match.
 *
 * <p>Appender releases newer than Boot's bundled OTel API line call
 * {@code LogRecordBuilder.setException(Throwable)} (added only in opentelemetry-api 1.61.0). Under
 * Boot 4.0.6's pinned api 1.55.0 that method is absent, so every WARN/ERROR log carrying a
 * {@link Throwable} throws {@link NoSuchMethodError} from inside {@code LoggingEventMapper} — and
 * because that's an {@link Error} (not an {@link Exception}), logback's {@code doAppend} does not
 * swallow it: it propagates and turns ordinary error paths into 500s / hung requests
 * (Spring Boot issue #50251). 2.28.0-alpha regressed this; 2.21.0-alpha matches 1.55.0.
 *
 * <p>This test fails fast if the appender is ever bumped out of step with the OTel API again.
 */
class OpenTelemetryLogbackAppenderCompatibilityTest {

    @Test
    void loggingAThrowableThroughTheOtelAppenderDoesNotThrowNoSuchMethodError() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);

        OpenTelemetryAppender appender = new OpenTelemetryAppender();
        appender.setContext(context);
        appender.start();
        root.addAppender(appender);
        // install() wires the (no-op) SDK onto the appender so subsequent events are mapped
        // immediately via LoggingEventMapper#mapLoggingEvent — the exact code path that breaks on a
        // version mismatch — rather than buffered.
        OpenTelemetryAppender.install(OpenTelemetry.noop());

        try {
            assertThatCode(() -> LoggerFactory.getLogger(OpenTelemetryLogbackAppenderCompatibilityTest.class)
                            .error(
                                    "guard: logging with a throwable must not blow up the appender",
                                    new RuntimeException("boom")))
                    .doesNotThrowAnyException();
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }
}
