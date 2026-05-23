package dev.tylercash.event.discord;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import net.dv8tion.jda.api.requests.RestAction;

/**
 * Replacement for {@code restAction.queue()} that keeps an {@link Observation} open
 * across the entire async HTTP round-trip rather than just the synchronous submit.
 *
 * <p>The problem: {@code RestAction.queue()} returns the instant JDA has accepted the
 * action — long before the HTTP actually fires. If the caller is inside an
 * {@code Observation.observe(...)} lambda, the observation closes the moment that
 * lambda returns, so by the time the OkHttp interceptor runs there's no live parent
 * observation in TL and the {@code discord.http} span emerges as a detached root.
 *
 * <p>The fix is to start an observation, open its scope, submit the action, and only
 * stop the observation when JDA reports the call completed (via the success/failure
 * callbacks). While the observation is open the existing
 * {@code ContextExecutorService}-wrapped JDA executors propagate it through to OkHttp.
 */
public final class JdaObservations {

    private JdaObservations() {}

    /**
     * Queue {@code action} under a new {@code spanName} observation, parented to whatever
     * is currently in TL (if any). Use this at call sites where the {@code .queue()} would
     * otherwise outlive the synchronous scope that produced it — typically anywhere inside
     * an {@code Observation.observe(...)} lambda or an offloaded listener body.
     */
    public static <T> void queue(RestAction<T> action, String spanName, ObservationRegistry registry) {
        Observation observation =
                Observation.createNotStarted(spanName, registry).start();
        try (Observation.Scope ignored = observation.openScope()) {
            action.queue(success -> observation.stop(), error -> {
                observation.error(error);
                observation.stop();
            });
        }
    }
}
