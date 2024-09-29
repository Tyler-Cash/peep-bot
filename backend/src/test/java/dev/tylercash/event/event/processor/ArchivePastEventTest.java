package dev.tylercash.event.event.processor;

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

import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Stream;

import static dev.tylercash.event.GlobalTestConfiguration.CLOCK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArchivePastEventTest {
    private static final ZonedDateTime eventArchivalTime = ZonedDateTime.now(CLOCK).minusDays(2);
    @Captor
    private ArgumentCaptor<Event> eventArgumentCaptor = ArgumentCaptor.forClass(Event.class);

    public static Stream<Arguments> archiveEvents() {
        List<Arguments> arguments = List.of(
                Arguments.of(true, // Event old enough to be archived
                        new Event(0, 0, 0, "Simple event", "", eventArchivalTime)),
                Arguments.of(
                        false, // Test that manually unarchived event isn't rearchived
                        new Event(0, 0, 0, "Simple event", "", eventArchivalTime)),
                Arguments.of(
                        false, // Event not old enough to be archived
                        new Event(0, 0, 0, "Simple event", "", ZonedDateTime.now(CLOCK))));
        ((Event) arguments.get(1).get()[1]).setState(EventState.ARCHIVED);
        return arguments.stream();
    }

    @ParameterizedTest
    @MethodSource("archiveEvents")
    void processEvent(boolean archived, Event event) {
        EventRepository eventRepository = mock(EventRepository.class);
        DiscordService discordService = mock(DiscordService.class);
        ArchivePastEvent archivePastEvent = new ArchivePastEvent(discordService, eventRepository, CLOCK);
        archivePastEvent.processEvent(event);
        int wantedNumberOfInvocations = archived ? 1 : 0;
        if (archived) {
            verify(discordService, times(wantedNumberOfInvocations)).archiveEventChannel(eventArgumentCaptor.capture());
            Event result = eventArgumentCaptor.getValue();
            assertEquals(EventState.ARCHIVED, result.getState());
        }
        verify(discordService, never()).deleteEventChannel(event);
    }
}