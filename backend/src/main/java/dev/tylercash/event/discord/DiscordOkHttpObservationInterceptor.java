package dev.tylercash.event.discord;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import lombok.NonNull;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Response;
import org.springframework.stereotype.Component;

/**
 * Wraps every JDA OkHttp call in an Observation so Discord REST round-trips show up as spans
 * (with method, host, route, and status). JDA owns its own OkHttp client, so the autoconfigured
 * RestClient instrumentation does not cover it.
 */
@Component
public class DiscordOkHttpObservationInterceptor implements Interceptor {

    private final ObservationRegistry observationRegistry;

    public DiscordOkHttpObservationInterceptor(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    @Override
    @NonNull
    public Response intercept(@NonNull Chain chain) throws IOException {
        HttpUrl url = chain.request().url();
        Observation observation = Observation.createNotStarted("discord.http", observationRegistry)
                .lowCardinalityKeyValue("http.method", chain.request().method())
                .lowCardinalityKeyValue("server.address", url.host())
                .lowCardinalityKeyValue("url.route", routeTemplate(url))
                .highCardinalityKeyValue("url.full", redact(url));
        observation.start();
        try (var scope = observation.openScope()) {
            Response response = chain.proceed(chain.request());
            observation.lowCardinalityKeyValue("http.status_code", Integer.toString(response.code()));
            if (!response.isSuccessful()) {
                observation.lowCardinalityKeyValue("outcome", "client-error");
            } else {
                observation.lowCardinalityKeyValue("outcome", "success");
            }
            return response;
        } catch (IOException | RuntimeException e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    /**
     * Collapse high-cardinality path segments (snowflakes, message IDs) into a low-cardinality
     * route template so spans aggregate. Discord IDs are long numeric strings.
     */
    private static String routeTemplate(HttpUrl url) {
        StringBuilder sb = new StringBuilder();
        for (String segment : url.pathSegments()) {
            sb.append('/');
            sb.append(isSnowflake(segment) ? "{id}" : segment);
        }
        return sb.isEmpty() ? "/" : sb.toString();
    }

    private static boolean isSnowflake(String segment) {
        if (segment.length() < 16 || segment.length() > 20) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            if (!Character.isDigit(segment.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String redact(HttpUrl url) {
        // Webhook tokens appear as path segments; the snowflake collapsing keeps them out of
        // low-card attrs, but url.full still leaks them. Strip the query string and any
        // segment that looks token-shaped (long, non-numeric, mixed case).
        StringBuilder sb = new StringBuilder(url.scheme()).append("://").append(url.host());
        for (String segment : url.pathSegments()) {
            sb.append('/');
            sb.append(isTokenish(segment) ? "{token}" : segment);
        }
        return sb.toString();
    }

    private static boolean isTokenish(String segment) {
        return segment.length() >= 40 && !isSnowflake(segment);
    }
}
