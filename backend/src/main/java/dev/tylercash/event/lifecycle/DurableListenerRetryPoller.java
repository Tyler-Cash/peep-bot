package dev.tylercash.event.lifecycle;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DurableListenerRetryPoller {

    private static final int BATCH = 100;
    private static final int STUCK_ATTEMPTS_THRESHOLD = 24;

    /**
     * How long an IN_PROGRESS row can sit before we assume the claiming worker died (JVM crash,
     * kill -9, network partition) and reset it to PENDING for retry. Comfortably longer than
     * {@link PostCommitDispatcher#INVOCATION_TIMEOUT_SECONDS} (60s) so we never race a live
     * invocation that's about to mark SUCCESS/FAILED.
     */
    private static final Duration STUCK_IN_PROGRESS_THRESHOLD = Duration.ofMinutes(5);

    private final ListenerInvocationRepository invocations;
    private final PostCommitDispatcher dispatcher;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    private final Map<String, AtomicLong> stuckCounters = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "DurableListenerRetryPoller", lockAtMostFor = "PT5M")
    public void retry() {
        Instant now = clock.instant();
        int reclaimed = invocations.reclaimStuckInProgress(now.minus(STUCK_IN_PROGRESS_THRESHOLD));
        if (reclaimed > 0) {
            log.warn("Reclaimed {} stuck IN_PROGRESS listener invocations", reclaimed);
        }
        List<ListenerInvocation> due = invocations.findDueForRetry(now, PageRequest.of(0, BATCH));
        if (due.isEmpty()) return;
        log.info("Retrying {} listener invocations", due.size());
        for (ListenerInvocation row : due) {
            try {
                dispatcher.invokeOnce(row);
            } catch (Exception e) {
                log.error(
                        "Unexpected error retrying listener '{}' for event {}",
                        row.getListenerName(),
                        row.getEventId(),
                        e);
            }
        }
    }

    @Scheduled(fixedDelay = 3_600_000)
    @SchedulerLock(name = "DurableListenerStuckCounter", lockAtMostFor = "PT10M")
    public void emitStuckGauges() {
        List<ListenerInvocation> stuck = invocations.findAll().stream()
                .filter(r ->
                        r.getStatus() == ListenerInvocationStatus.FAILED && r.getAttempts() > STUCK_ATTEMPTS_THRESHOLD)
                .toList();
        log.info("Stuck listener invocations: {}", stuck.size());

        Map<String, Long> byListener = new HashMap<>();
        for (ListenerInvocation row : stuck) {
            byListener.merge(row.getListenerName(), 1L, Long::sum);
        }

        byListener.forEach((name, count) -> stuckGauge(name).set(count));

        // Zero out gauges for listeners that no longer have stuck rows
        stuckCounters.forEach((name, gauge) -> {
            if (!byListener.containsKey(name)) gauge.set(0);
        });
    }

    private AtomicLong stuckGauge(String listenerName) {
        return stuckCounters.computeIfAbsent(listenerName, name -> {
            AtomicLong counter = new AtomicLong(0);
            meterRegistry.gauge("event.lifecycle.listener.stuck", Tags.of("listener", name), counter, AtomicLong::get);
            return counter;
        });
    }
}
