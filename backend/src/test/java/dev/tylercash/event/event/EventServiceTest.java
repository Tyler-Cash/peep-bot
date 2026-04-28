package dev.tylercash.event.event;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.event.model.AttendanceSummary;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.statemachine.EventStateMachineEvent;
import dev.tylercash.event.event.statemachine.EventStateMachineService;
import dev.tylercash.event.rewind.EmbeddingService;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    private static Event buildEvent() {
        return new Event(0L, 0L, 0L, "Test Event", "creator", ZonedDateTime.now(), "description");
    }

    private EventService createService(
            EventRepository eventRepository,
            DiscordService discordService,
            AttendanceService attendanceService,
            DiscordUserCacheService discordUserCacheService) {
        return new EventService(
                discordService,
                eventRepository,
                mock(EventStateMachineService.class),
                Clock.systemDefaultZone(),
                attendanceService,
                discordUserCacheService,
                mock(EmbeddingService.class));
    }

    @Test
    void removeAttendee_delegatesToAttendanceService() {
        EventRepository eventRepository = mock(EventRepository.class);
        DiscordService discordService = mock(DiscordService.class);
        AttendanceService attendanceService = mock(AttendanceService.class);
        DiscordUserCacheService cacheService = mock(DiscordUserCacheService.class);
        EventService service = createService(eventRepository, discordService, attendanceService, cacheService);

        Event event = buildEvent();
        UUID id = UUID.randomUUID();
        event.setId(id);
        when(eventRepository.findById(id)).thenReturn(Optional.of(event));
        when(attendanceService.getCurrentAttendance(id))
                .thenReturn(new AttendanceSummary(List.of(), List.of(), List.of()));
        when(cacheService.getDisplayNames(any())).thenReturn(Map.of());

        service.removeAttendee(id, "12345", null);

        verify(attendanceService).removeAttendee(id, "12345", null);
    }

    @Test
    void removeAttendee_byName_delegatesToAttendanceService() {
        EventRepository eventRepository = mock(EventRepository.class);
        DiscordService discordService = mock(DiscordService.class);
        AttendanceService attendanceService = mock(AttendanceService.class);
        DiscordUserCacheService cacheService = mock(DiscordUserCacheService.class);
        EventService service = createService(eventRepository, discordService, attendanceService, cacheService);

        Event event = buildEvent();
        UUID id = UUID.randomUUID();
        event.setId(id);
        when(eventRepository.findById(id)).thenReturn(Optional.of(event));
        when(attendanceService.getCurrentAttendance(id))
                .thenReturn(new AttendanceSummary(List.of(), List.of(), List.of()));
        when(cacheService.getDisplayNames(any())).thenReturn(Map.of());

        service.removeAttendee(id, null, "[+1] Dave");

        verify(attendanceService).removeAttendee(id, null, "[+1] Dave");
    }

    @Test
    void removeAttendee_whenCompleted_throwsForbidden() {
        EventRepository eventRepository = mock(EventRepository.class);
        DiscordService discordService = mock(DiscordService.class);
        AttendanceService attendanceService = mock(AttendanceService.class);
        DiscordUserCacheService cacheService = mock(DiscordUserCacheService.class);
        EventService service = createService(eventRepository, discordService, attendanceService, cacheService);

        Event event = buildEvent();
        event.setState(EventState.POST_COMPLETED);
        UUID id = UUID.randomUUID();
        when(eventRepository.findById(id)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.removeAttendee(id, "12345", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Attendance is locked");

        verifyNoInteractions(attendanceService);
    }

    @Test
    void cancelEvent_throwsBadRequest_whenEventAlreadyCompleted() {
        EventRepository eventRepository = mock(EventRepository.class);
        DiscordService discordService = mock(DiscordService.class);
        EventStateMachineService stateMachineService = mock(EventStateMachineService.class);
        EventService service = new EventService(
                discordService,
                eventRepository,
                stateMachineService,
                Clock.systemDefaultZone(),
                mock(AttendanceService.class),
                mock(DiscordUserCacheService.class),
                mock(EmbeddingService.class));

        Event event = buildEvent();
        event.setState(EventState.POST_COMPLETED);
        UUID id = UUID.randomUUID();
        when(eventRepository.findById(id)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.cancelEvent(id))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already completed or cancelled");

        verifyNoInteractions(stateMachineService);
    }

    @Test
    void cancelEvent_callsAttemptTransition_withCancelEvent() {
        EventRepository eventRepository = mock(EventRepository.class);
        DiscordService discordService = mock(DiscordService.class);
        EventStateMachineService stateMachineService = mock(EventStateMachineService.class);
        EventService service = new EventService(
                discordService,
                eventRepository,
                stateMachineService,
                Clock.systemDefaultZone(),
                mock(AttendanceService.class),
                mock(DiscordUserCacheService.class),
                mock(EmbeddingService.class));

        Event event = buildEvent();
        UUID id = UUID.randomUUID();
        when(eventRepository.findById(id)).thenReturn(Optional.of(event));
        when(stateMachineService.attemptTransition(event, EventStateMachineEvent.CANCEL))
                .thenReturn(true);

        service.cancelEvent(id);

        verify(stateMachineService).attemptTransition(event, EventStateMachineEvent.CANCEL);
    }

    @Test
    void cancelEvent_throwsInternalServerError_whenTransitionFails() {
        EventRepository eventRepository = mock(EventRepository.class);
        DiscordService discordService = mock(DiscordService.class);
        EventStateMachineService stateMachineService = mock(EventStateMachineService.class);
        EventService service = new EventService(
                discordService,
                eventRepository,
                stateMachineService,
                Clock.systemDefaultZone(),
                mock(AttendanceService.class),
                mock(DiscordUserCacheService.class),
                mock(EmbeddingService.class));

        Event event = buildEvent();
        UUID id = UUID.randomUUID();
        when(eventRepository.findById(id)).thenReturn(Optional.of(event));
        when(stateMachineService.attemptTransition(event, EventStateMachineEvent.CANCEL))
                .thenReturn(false);

        assertThatThrownBy(() -> service.cancelEvent(id))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Failed to cancel event");
    }

    @Test
    void recategorizeEvent_callsEmbeddingServiceClassify() {
        EventRepository eventRepository = mock(EventRepository.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        EventService service = new EventService(
                mock(DiscordService.class),
                eventRepository,
                mock(EventStateMachineService.class),
                Clock.systemDefaultZone(),
                mock(AttendanceService.class),
                mock(DiscordUserCacheService.class),
                embeddingService);

        Event event = buildEvent();
        UUID id = UUID.randomUUID();
        event.setId(id);
        when(eventRepository.findById(id)).thenReturn(Optional.of(event));

        service.recategorizeEvent(id);

        verify(embeddingService).classifyEvent(event);
    }
}
