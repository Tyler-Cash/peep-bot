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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static dev.tylercash.event.GlobalTestConfiguration.CLOCK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeleteEventArchiveTest {
    private static final ZonedDateTime eventDeletionTime = ZonedDateTime.now(CLOCK).minusMonths(4);
    @Captor
    private ArgumentCaptor<Event> eventArgumentCaptor = ArgumentCaptor.forClass(Event.class);

    public static Stream<Arguments> deleteEvents() {
        Event eventToBeDeleted = new Event(0, 0, 0, "Simple event", "", eventDeletionTime);
        List<Arguments> arguments = new ArrayList<>(List.of(
                Arguments.of(true, // Event old enough to be deleted
                        eventToBeDeleted),
                Arguments.of(
                        false, // Event not old enough to be archived
                        new Event(0, 0, 0, "Simple event", "", ZonedDateTime.now(CLOCK)))));
        for (Arguments argument : arguments) {
            ((Event) argument.get()[1]).setState(EventState.ARCHIVED);
        }
        arguments.add(Arguments.of(false, eventToBeDeleted)); // Event not in archived state
        return arguments.stream();
    }

    @ParameterizedTest
    @MethodSource("deleteEvents")
    void processEvent(boolean deleted, Event event) {
        EventRepository eventRepository = mock(EventRepository.class);
        DiscordService discordService = mock(DiscordService.class);
        DeleteEventArchive deleteEventArchive = new DeleteEventArchive(discordService, eventRepository, CLOCK);
        deleteEventArchive.processEvent(event);
        int wantedNumberOfInvocations = deleted ? 1 : 0;
        if (deleted) {
            verify(discordService, times(wantedNumberOfInvocations)).deleteEventChannel(eventArgumentCaptor.capture());
            Event result = eventArgumentCaptor.getValue();
            assertEquals(EventState.DELETED, result.getState());
        }
        verify(discordService, never()).archiveEventChannel(event);
    }
}