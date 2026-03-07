package dev.tylercash.event.discord.listener;

import static dev.tylercash.event.discord.listener.ButtonInteractionListener.ACCEPTED;
import static dev.tylercash.event.discord.listener.ButtonInteractionListener.MODAL_PLACEHOLDER;
import static dev.tylercash.event.discord.listener.ModalInteractionListener.PLUS_ONE;
import static dev.tylercash.event.discord.listener.ModalInteractionListener.PLUS_ONE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.discord.EmbedService;
import dev.tylercash.event.event.AttendanceService;
import dev.tylercash.event.event.EventService;
import dev.tylercash.event.event.model.AttendanceStatus;
import dev.tylercash.event.event.model.Event;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import net.dv8tion.jda.api.components.ModalTopLevelComponentUnion;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.requests.restaction.interactions.MessageEditCallbackAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ModalCallbackAction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class ButtonInteractionListenerTest {
    final ArgumentCaptor<Modal> modalArgumentCaptor = ArgumentCaptor.forClass(Modal.class);
    final long messageId = 352353L;

    @Test
    void onButtonInteractionWhenAcceptingEvent() {
        Clock fixedClock = Clock.fixed(Instant.parse("2025-01-01T12:00:00Z"), ZoneId.of("UTC"));
        EventRepository eventRepository = mock(EventRepository.class);
        ButtonInteractionEvent buttonInteractionEvent = mock(ButtonInteractionEvent.class);
        EmbedService embedService = mock(EmbedService.class);
        EventService eventService = mock(EventService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<EventService> eventServiceProvider = mock(ObjectProvider.class);
        when(eventServiceProvider.getObject()).thenReturn(eventService);
        AttendanceService attendanceService = mock(AttendanceService.class);
        DiscordUserCacheService discordUserCacheService = mock(DiscordUserCacheService.class);

        ButtonInteractionListener listener = new ButtonInteractionListener(
                fixedClock,
                ObservationRegistry.NOOP,
                eventRepository,
                embedService,
                eventServiceProvider,
                attendanceService,
                discordUserCacheService,
                mock(dev.tylercash.event.discord.DiscordService.class));

        String nickname = "testNickname";
        String snowflake = "38943984983";

        Event event = mock(Event.class);
        UUID eventId = UUID.randomUUID();
        when(event.getMessageId()).thenReturn(messageId);
        when(event.getId()).thenReturn(eventId);
        when(event.getName()).thenReturn("Test Event");
        when(event.getDateTime()).thenReturn(ZonedDateTime.parse("2025-01-01T13:00:00Z"));

        when(buttonInteractionEvent.getMember()).thenReturn(mock(Member.class));
        when(buttonInteractionEvent.getMember().getNickname()).thenReturn(nickname);
        when(buttonInteractionEvent.getMember().getId()).thenReturn(snowflake);
        when(buttonInteractionEvent.getMember().getEffectiveName()).thenReturn(nickname);

        when(buttonInteractionEvent.getButton()).thenReturn(mock(Button.class));
        when(buttonInteractionEvent.getButton().getCustomId()).thenReturn(ACCEPTED);
        when(buttonInteractionEvent.getMessageIdLong()).thenReturn(messageId);
        when(eventRepository.findByMessageId(messageId)).thenReturn(event);
        when(eventService.isCompleted(event)).thenReturn(false);
        when(embedService.getMessage(event, fixedClock)).thenReturn(List.of(mock(MessageEmbed.class)));
        when(buttonInteractionEvent.editMessageEmbeds(embedService.getMessage(event, fixedClock)))
                .thenReturn(mock(MessageEditCallbackAction.class));

        listener.onButtonInteraction(buttonInteractionEvent);

        verify(discordUserCacheService).upsertUser(snowflake, nickname);
        verify(attendanceService).flipAttendance(eventId, snowflake, null, AttendanceStatus.ACCEPTED);
        verify(eventService).populateAttendance(event);
        verify(buttonInteractionEvent).editMessageEmbeds(embedService.getMessage(event, fixedClock));
    }

    @Test
    void onButtonInteractionEventDoesntExist() {
        EventRepository eventRepository = mock(EventRepository.class);
        ButtonInteractionEvent buttonInteractionEvent = mock(ButtonInteractionEvent.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<EventService> esp = mock(ObjectProvider.class);
        ButtonInteractionListener listener = new ButtonInteractionListener(
                mock(Clock.class),
                ObservationRegistry.NOOP,
                eventRepository,
                mock(EmbedService.class),
                esp,
                mock(AttendanceService.class),
                mock(DiscordUserCacheService.class),
                mock(dev.tylercash.event.discord.DiscordService.class));

        when(buttonInteractionEvent.getMessageIdLong()).thenReturn(messageId);
        when(buttonInteractionEvent.getButton()).thenReturn(mock(Button.class));

        listener.onButtonInteraction(buttonInteractionEvent);
        verify(eventRepository).findByMessageId(messageId);
        verifyNoMoreInteractions(eventRepository);
        verifyNoInteractions(buttonInteractionEvent.getButton());
    }

    @Test
    void onButtonInteractionWhenReturnModal() {
        Clock fixedClock = Clock.fixed(Instant.parse("2025-01-01T12:00:00Z"), ZoneId.of("UTC"));
        EventRepository eventRepository = mock(EventRepository.class);
        ButtonInteractionEvent buttonInteractionEvent = mock(ButtonInteractionEvent.class);
        ModalCallbackAction modalCallbackAction = mock(ModalCallbackAction.class);
        EventService eventService = mock(EventService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<EventService> esp = mock(ObjectProvider.class);
        when(esp.getObject()).thenReturn(eventService);

        ButtonInteractionListener listener = new ButtonInteractionListener(
                fixedClock,
                ObservationRegistry.NOOP,
                eventRepository,
                mock(EmbedService.class),
                esp,
                mock(AttendanceService.class),
                mock(DiscordUserCacheService.class),
                mock(dev.tylercash.event.discord.DiscordService.class));

        Event futureEvent = mock(Event.class);
        when(futureEvent.getDateTime()).thenReturn(ZonedDateTime.parse("2025-01-01T13:00:00Z"));
        when(eventService.isCompleted(futureEvent)).thenReturn(false);
        when(buttonInteractionEvent.getButton()).thenReturn(mock(Button.class));
        when(buttonInteractionEvent.getButton().getCustomId()).thenReturn(PLUS_ONE_ID);
        when(buttonInteractionEvent.replyModal(any())).thenReturn(modalCallbackAction);
        when(eventRepository.findByMessageId(any())).thenReturn(futureEvent);

        listener.onButtonInteraction(buttonInteractionEvent);

        verify(buttonInteractionEvent, atLeastOnce()).replyModal(modalArgumentCaptor.capture());
        List<Modal> actual = modalArgumentCaptor.getAllValues();
        assertEquals(1, actual.size());
        assertEquals(PLUS_ONE_ID, actual.get(0).getId());
        assertEquals(PLUS_ONE, actual.get(0).getTitle());
        List<ModalTopLevelComponentUnion> components = actual.get(0).getComponents();
        assertEquals(1, components.size());
        Label label = (Label) components.get(0);
        assertEquals(PLUS_ONE, label.getLabel());
        TextInput textInput = (TextInput) label.getChild();
        assertEquals(PLUS_ONE_ID, textInput.getCustomId());
        assertEquals(MODAL_PLACEHOLDER, textInput.getPlaceHolder());
    }
}
