package dev.tylercash.event.event;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {
    final static private Clock clock = Clock.fixed(Instant.ofEpochMilli(1515236400000L), ZoneId.systemDefault());
    final static private LocalDateTime eventArchivalTime = LocalDateTime.now(clock).minusDays(2);
    final static private LocalDateTime eventDeletionTime = LocalDateTime.now(clock).minusMonths(4);
    @Captor
    private ArgumentCaptor<Event> eventArgumentCaptor = ArgumentCaptor.forClass(Event.class);

    public static Stream<Arguments> deleteEvents() {
        List<Arguments> arguments = List.of(
                Arguments.of(true, // Event old enough to be deleted
                        new Event(0, 0, 0, "Simple event", "", eventDeletionTime)),
                Arguments.of(
                        false, // Event not old enough to be archived
                        new Event(0, 0, 0, "Simple event", "", LocalDateTime.now(clock))));
        for (Arguments argument : arguments) {
            ((Event) argument.get()[1]).setState(EventState.ARCHIVED);
        }
        return arguments.stream();
    }

    public static Stream<Arguments> archiveEvents() {
        List<Arguments> arguments = List.of(
                Arguments.of(true, // Event old enough to be archived
                        new Event(0, 0, 0, "Simple event", "", eventArchivalTime)),
                Arguments.of(
                        false, // Test that manually unarchived event isn't rearchived
                        new Event(0, 0, 0, "Simple event", "", eventArchivalTime)),
                Arguments.of(
                        false, // Event not old enough to be archived
                        new Event(0, 0, 0, "Simple event", "", LocalDateTime.now(clock))));
        ((Event) arguments.get(1).get()[1]).setState(EventState.ARCHIVED);
        return arguments.stream();
    }

    @ParameterizedTest
    @MethodSource("deleteEvents")
    void deleteEventSchedule(boolean deleted, Event event) {
        EventRepository eventRepository = mock(EventRepository.class);
        DiscordService discordService = mock(DiscordService.class);
        EventService eventService = new EventService(discordService, eventRepository, clock);
        eventService.deleteEvent(event);
        int wantedNumberOfInvocations = deleted ? 1 : 0;
        if (deleted) {
            verify(discordService, times(wantedNumberOfInvocations)).deleteEventChannel(eventArgumentCaptor.capture());
            Event result = eventArgumentCaptor.getValue();
            assertEquals(EventState.DELETED, result.getState());
        }
        verify(discordService, never()).archiveEventChannel(event);
    }

    @ParameterizedTest
    @MethodSource("archiveEvents")
    void archiveEvent(boolean archived, Event event) {
        EventRepository eventRepository = mock(EventRepository.class);
        DiscordService discordService = mock(DiscordService.class);
        EventService eventService = new EventService(discordService, eventRepository, clock);
        eventService.archiveEvent(event);
        int wantedNumberOfInvocations = archived ? 1 : 0;
        if (archived) {
            verify(discordService, times(wantedNumberOfInvocations)).archiveEventChannel(eventArgumentCaptor.capture());
            Event result = eventArgumentCaptor.getValue();
            assertEquals(EventState.ARCHIVED, result.getState());
        }
        verify(discordService, never()).deleteEventChannel(event);
    }
}