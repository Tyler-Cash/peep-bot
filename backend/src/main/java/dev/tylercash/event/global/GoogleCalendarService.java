package dev.tylercash.event.global;

import dev.tylercash.event.event.model.Event;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class GoogleCalendarService {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    public static String getCalendarEventUrl(Event event) {
        OffsetDateTime eventTime =
                event.getDateTime().truncatedTo(ChronoUnit.MINUTES).toOffsetDateTime();
        String start = eventTime.format(FORMATTER);
        String end = eventTime.plusHours(1).format(FORMATTER);
        UriComponentsBuilder builder = UriComponentsBuilder.newInstance()
                .scheme("https")
                .host("www.google.com")
                .path("/calendar/event")
                .queryParam("action", "TEMPLATE")
                .queryParam("text", event.getName())
                .queryParam("dates", start + "/" + end);
        if (event.getLocation() != null && !event.getLocation().isBlank()) {
            builder.queryParam("location", event.getLocation());
        }
        return builder.encode().build().toUri().toString();
    }
}
