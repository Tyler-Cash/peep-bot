package dev.tylercash.event.event;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Attendee;
import dev.tylercash.event.event.model.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    private static Event buildEvent() {
        return new Event(0L, 0L, 0L, "Test Event", "creator", ZonedDateTime.now(), "description");
    }

    @Test
    void removeAttendee_bySnowflake_removesFromAccepted() {
        EventRepository eventRepository = mock(EventRepository.class);
        DiscordService discordService = mock(DiscordService.class);
        EventService service = new EventService(discordService, eventRepository);

        Event event = buildEvent();
        event.getAccepted().add(Attendee.createDiscordAttendee("12345", "Alice"));

        UUID id = UUID.randomUUID();
        when(eventRepository.findById(id)).thenReturn(Optional.of(event));

        service.removeAttendee(id, "12345", null);

        assertThat(event.getAccepted()).isEmpty();
        verify(eventRepository).save(event);
    }

    @Test
    void removeAttendee_bySnowflake_removesFromDeclined() {
        EventRepository eventRepository = mock(EventRepository.class);
        DiscordService discordService = mock(DiscordService.class);
        EventService service = new EventService(discordService, eventRepository);

        Event event = buildEvent();
        event.getDeclined().add(Attendee.createDiscordAttendee("99999", "Bob"));

        UUID id = UUID.randomUUID();
        when(eventRepository.findById(id)).thenReturn(Optional.of(event));

        service.removeAttendee(id, "99999", null);

        assertThat(event.getDeclined()).isEmpty();
        verify(eventRepository).save(event);
    }

    @Test
    void removeAttendee_bySnowflake_removesFromMaybe() {
        EventRepository eventRepository = mock(EventRepository.class);
        DiscordService discordService = mock(DiscordService.class);
        EventService service = new EventService(discordService, eventRepository);

        Event event = buildEvent();
        event.getMaybe().add(Attendee.createDiscordAttendee("77777", "Carol"));

        UUID id = UUID.randomUUID();
        when(eventRepository.findById(id)).thenReturn(Optional.of(event));

        service.removeAttendee(id, "77777", null);

        assertThat(event.getMaybe()).isEmpty();
        verify(eventRepository).save(event);
    }

    @Test
    void removeAttendee_byName_removesGuestWithNullSnowflake() {
        EventRepository eventRepository = mock(EventRepository.class);
        DiscordService discordService = mock(DiscordService.class);
        EventService service = new EventService(discordService, eventRepository);

        Event event = buildEvent();
        // createDiscordAttendee(null, name) creates "[+1] name" as the stored name
        Attendee guest = Attendee.createDiscordAttendee(null, "Dave");
        event.getAccepted().add(guest);

        UUID id = UUID.randomUUID();
        when(eventRepository.findById(id)).thenReturn(Optional.of(event));

        service.removeAttendee(id, null, "[+1] Dave");

        assertThat(event.getAccepted()).isEmpty();
        verify(eventRepository).save(event);
    }

    @Test
    void removeAttendee_leavesOtherAttendeesUntouched() {
        EventRepository eventRepository = mock(EventRepository.class);
        DiscordService discordService = mock(DiscordService.class);
        EventService service = new EventService(discordService, eventRepository);

        Event event = buildEvent();
        Attendee target = Attendee.createDiscordAttendee("11111", "Target");
        Attendee other = Attendee.createDiscordAttendee("22222", "Other");
        event.getAccepted().add(target);
        event.getAccepted().add(other);

        UUID id = UUID.randomUUID();
        when(eventRepository.findById(id)).thenReturn(Optional.of(event));

        service.removeAttendee(id, "11111", null);

        assertThat(event.getAccepted()).hasSize(1);
        assertThat(event.getAccepted().iterator().next().getSnowflake()).isEqualTo("22222");
    }

    @Test
    void removeAttendee_whenSnowflakeNotFound_stillSavesAndRetainsAttendees() {
        EventRepository eventRepository = mock(EventRepository.class);
        DiscordService discordService = mock(DiscordService.class);
        EventService service = new EventService(discordService, eventRepository);

        Event event = buildEvent();
        Attendee attendee = Attendee.createDiscordAttendee("11111", "Alice");
        event.getAccepted().add(attendee);

        UUID id = UUID.randomUUID();
        when(eventRepository.findById(id)).thenReturn(Optional.of(event));

        // Remove a snowflake that doesn't exist on the event
        service.removeAttendee(id, "99999", null);

        assertThat(event.getAccepted()).hasSize(1);
        verify(eventRepository).save(event);
    }

    @Test
    void removeAttendee_blankSnowflakeFallsBackToNameMatch() {
        EventRepository eventRepository = mock(EventRepository.class);
        DiscordService discordService = mock(DiscordService.class);
        EventService service = new EventService(discordService, eventRepository);

        Event event = buildEvent();
        Attendee guest = Attendee.createDiscordAttendee(null, "Eve");
        event.getMaybe().add(guest);

        UUID id = UUID.randomUUID();
        when(eventRepository.findById(id)).thenReturn(Optional.of(event));

        // Empty snowflake should fall through to name matching
        service.removeAttendee(id, "  ", "[+1] Eve");

        assertThat(event.getMaybe()).isEmpty();
        verify(eventRepository).save(event);
    }
}
