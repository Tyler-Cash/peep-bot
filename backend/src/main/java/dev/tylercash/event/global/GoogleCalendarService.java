package dev.tylercash.event.global;

import dev.tylercash.event.event.model.Event;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

@Service
public class GoogleCalendarService {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    public static String getCalendarEventUrl(Event event) {
        OffsetDateTime eventTime = event.getDateTime().atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
        String start = eventTime.format(FORMATTER);
        String end = eventTime.plusHours(1).format(FORMATTER);
        return UriComponentsBuilder.newInstance()
                .scheme("https")
                .host("www.google.com")
                .path("/calendar/event")
                .queryParam("action", "TEMPLATE")
                .queryParam("text", event.getName())
                .queryParam("location", event.getLocation())
                .queryParam("dates", start + "/" + end)
                .queryParam("details", event.getDescription())
                .encode()
                .build()
                .toUri()
                .toString();
    }
}
