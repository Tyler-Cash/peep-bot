package dev.tylercash.event.discord;

import dev.tylercash.event.event.model.Attendee;
import dev.tylercash.event.event.model.Event;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EmbedRendererTest {

    private static final String FRONTEND_URL = "https://event.tylercash.dev/";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2025-06-15T10:00:00Z"), ZoneId.of("UTC"));

    private static Event buildEvent() {
        Event event = new Event(0L, 0L, 0L, "Test Event", "creator",
                ZonedDateTime.of(2025, 7, 1, 18, 0, 0, 0, ZoneId.of("UTC")), "A test event");
        event.setLocation("");
        return event;
    }

    @Test
    void embedContainsEditEventLink() {
        Event event = buildEvent();
        UUID eventId = UUID.randomUUID();
        event.setId(eventId);

        EmbedBuilder embedBuilder = new EmbedRenderer(event, FIXED_CLOCK, FRONTEND_URL).getEmbedBuilder();
        MessageEmbed embed = embedBuilder.build();

        List<MessageEmbed.Field> fields = embed.getFields();
        MessageEmbed.Field linksField = fields.stream()
                .filter(f -> "Links".equals(f.getName()))
                .findFirst()
                .orElse(null);

        assertThat(linksField).isNotNull();
        assertThat(linksField.getValue()).contains("[Edit event](" + FRONTEND_URL + "event/" + eventId + ")");
    }

    @Test
    void embedStillContainsGoogleCalendarLink() {
        Event event = buildEvent();
        event.setId(UUID.randomUUID());

        EmbedBuilder embedBuilder = new EmbedRenderer(event, FIXED_CLOCK, FRONTEND_URL).getEmbedBuilder();
        MessageEmbed embed = embedBuilder.build();

        List<MessageEmbed.Field> fields = embed.getFields();
        MessageEmbed.Field linksField = fields.stream()
                .filter(f -> "Links".equals(f.getName()))
                .findFirst()
                .orElse(null);

        assertThat(linksField).isNotNull();
        assertThat(linksField.getValue()).contains("[Add to Google calendar](");
    }

    @Test
    void editLinkUsesCorrectFrontendUrl() {
        Event event = buildEvent();
        UUID eventId = UUID.randomUUID();
        event.setId(eventId);
        String customFrontendUrl = "http://localhost:5173/";

        EmbedBuilder embedBuilder = new EmbedRenderer(event, FIXED_CLOCK, customFrontendUrl).getEmbedBuilder();
        MessageEmbed embed = embedBuilder.build();

        List<MessageEmbed.Field> fields = embed.getFields();
        MessageEmbed.Field linksField = fields.stream()
                .filter(f -> "Links".equals(f.getName()))
                .findFirst()
                .orElse(null);

        assertThat(linksField).isNotNull();
        assertThat(linksField.getValue()).contains("[Edit event](http://localhost:5173/event/" + eventId + ")");
    }

    @Test
    void embedContainsEventTitle() {
        Event event = buildEvent();
        event.setId(UUID.randomUUID());

        EmbedBuilder embedBuilder = new EmbedRenderer(event, FIXED_CLOCK, FRONTEND_URL).getEmbedBuilder();
        MessageEmbed embed = embedBuilder.build();

        assertThat(embed.getTitle()).isEqualTo("Test Event");
    }

    @Test
    void embedContainsLocationFieldWhenLocationIsSet() {
        Event event = buildEvent();
        event.setId(UUID.randomUUID());
        event.setLocation("Sydney");

        EmbedBuilder embedBuilder = new EmbedRenderer(event, FIXED_CLOCK, FRONTEND_URL).getEmbedBuilder();
        MessageEmbed embed = embedBuilder.build();

        MessageEmbed.Field locationField = embed.getFields().stream()
                .filter(f -> "Location".equals(f.getName()))
                .findFirst()
                .orElse(null);

        assertThat(locationField).isNotNull();
        assertThat(locationField.getValue()).isEqualTo("Sydney");
    }

    @Test
    void embedOmitsLocationFieldWhenLocationIsBlank() {
        Event event = buildEvent();
        event.setId(UUID.randomUUID());
        event.setLocation("   ");

        EmbedBuilder embedBuilder = new EmbedRenderer(event, FIXED_CLOCK, FRONTEND_URL).getEmbedBuilder();
        MessageEmbed embed = embedBuilder.build();

        boolean hasLocation = embed.getFields().stream()
                .anyMatch(f -> "Location".equals(f.getName()));

        assertThat(hasLocation).isFalse();
    }

    @Test
    void embedShowsAcceptedAttendees() {
        Event event = buildEvent();
        event.setId(UUID.randomUUID());
        event.getAccepted().add(Attendee.createDiscordAttendee("12345", "Alice"));

        EmbedBuilder embedBuilder = new EmbedRenderer(event, FIXED_CLOCK, FRONTEND_URL).getEmbedBuilder();
        MessageEmbed embed = embedBuilder.build();

        MessageEmbed.Field acceptedField = embed.getFields().stream()
                .filter(f -> f.getName() != null && f.getName().contains("Accepted"))
                .findFirst()
                .orElse(null);

        assertThat(acceptedField).isNotNull();
        assertThat(acceptedField.getValue()).contains("Alice");
    }
}
