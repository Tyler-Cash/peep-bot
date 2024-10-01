package dev.tylercash.event.discord.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.EmbedService;
import dev.tylercash.event.event.model.Attendee;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.global.MetricsService;
import io.micrometer.core.instrument.Timer;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.components.LayoutComponent;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.requests.restaction.interactions.MessageEditCallbackAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ModalCallbackAction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;

import static dev.tylercash.event.discord.listener.ButtonInteractionListener.ACCEPTED;
import static dev.tylercash.event.discord.listener.ButtonInteractionListener.MODAL_PLACEHOLDER;
import static dev.tylercash.event.discord.listener.ModalInteractionListener.PLUS_ONE;
import static dev.tylercash.event.discord.listener.ModalInteractionListener.PLUS_ONE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ButtonInteractionListenerTest {
    final ArgumentCaptor<Modal> modalArgumentCaptor = ArgumentCaptor.forClass(Modal.class);
    final ArgumentCaptor<Event> eventArgumentCaptor = ArgumentCaptor.forClass(Event.class);
    final long messageId = 352353L;

    @Test
    void onButtonInteractionWhenAcceptingEvent() {
        Result result = new Result(mock(Clock.class), mock(MetricsService.class), mock(EventRepository.class), mock(ButtonInteractionEvent.class), mock(ModalCallbackAction.class), mock(EmbedService.class));
        ButtonInteractionListener buttonInteractionListener = new ButtonInteractionListener(result.clock, result.metricsService, result.eventRepository, result.embedService);
        String nickname = "testNickname";
        String snowflake = "38943984983";

        Event event = mock(Event.class);
        when(event.getMessageId()).thenReturn(messageId);
        when(event.getAccepted()).thenReturn(new HashSet<>());

        when(result.buttonInteractionEvent.getMember()).thenReturn(mock(Member.class));
        when(result.buttonInteractionEvent.getMember().getNickname()).thenReturn(nickname);
        when(result.buttonInteractionEvent.getMember().getId()).thenReturn(snowflake);

        when(result.buttonInteractionEvent.getButton()).thenReturn(mock(Button.class));
        when(result.buttonInteractionEvent.getButton().getId()).thenReturn(ACCEPTED);
        when(result.buttonInteractionEvent.getMessageIdLong()).thenReturn(messageId);
        when(result.eventRepository.findByMessageId(messageId)).thenReturn(event);
        when(result.embedService.getMessage(event, result.clock)).thenReturn(List.of(mock(MessageEmbed.class)));
        when(result.buttonInteractionEvent.editMessageEmbeds(result.embedService.getMessage(event, result.clock))).thenReturn(mock(MessageEditCallbackAction.class));
        when(result.metricsService.getDiscordMessageComponentEventTimer()).thenReturn(mock(Timer.class));

        buttonInteractionListener.onButtonInteraction(result.buttonInteractionEvent());

        verify(result.embedService, times(2)).getMessage(event, result.clock);
        verify(result.eventRepository).save(eventArgumentCaptor.capture());
        verify(result.buttonInteractionEvent).editMessageEmbeds(result.embedService.getMessage(event, result.clock));
        verify(result.buttonInteractionEvent.editMessageEmbeds(result.embedService.getMessage(event, result.clock))).complete();
        List<Event> actuals = eventArgumentCaptor.getAllValues();
        assertEquals(1, actuals.size());
        assertEquals(messageId, actuals.get(0).getMessageId());
        assertEquals(nickname, ((Attendee) actuals.get(0).getAccepted().toArray()[0]).getName());
        assertEquals(snowflake, ((Attendee) actuals.get(0).getAccepted().toArray()[0]).getSnowflake());
    }

    @Test
    void onButtonInteractionEventDoesntExist() {
        Result result = new Result(mock(Clock.class), mock(MetricsService.class), mock(EventRepository.class), mock(ButtonInteractionEvent.class), mock(ModalCallbackAction.class), mock(EmbedService.class));
        ButtonInteractionListener buttonInteractionListener = new ButtonInteractionListener(result.clock, result.metricsService, result.eventRepository, result.embedService);

        when(result.buttonInteractionEvent.getMessageIdLong()).thenReturn(messageId);
        when(result.buttonInteractionEvent.getButton()).thenReturn(mock(Button.class));

        buttonInteractionListener.onButtonInteraction(result.buttonInteractionEvent());
        verify(result.eventRepository).findByMessageId(messageId);
        verifyNoMoreInteractions(result.eventRepository);
        verifyNoInteractions(result.buttonInteractionEvent.getButton());
        verifyNoInteractions(result.metricsService);
    }

    @Test
    void onButtonInteractionWhenReturnModal() {
        Result result = new Result(mock(Clock.class), mock(MetricsService.class), mock(EventRepository.class), mock(ButtonInteractionEvent.class), mock(ModalCallbackAction.class), mock(EmbedService.class));
        ButtonInteractionListener buttonInteractionListener = new ButtonInteractionListener(result.clock, result.metricsService, result.eventRepository, result.embedService);
        when(result.buttonInteractionEvent.getButton()).thenReturn(mock(Button.class));
        when(result.buttonInteractionEvent.getButton().getId()).thenReturn(PLUS_ONE_ID);
        when(result.buttonInteractionEvent.replyModal(any())).thenReturn(result.modalCallbackAction());
        when(result.eventRepository.findByMessageId(any())).thenReturn(mock(Event.class));

        buttonInteractionListener.onButtonInteraction(result.buttonInteractionEvent());

        verify(result.buttonInteractionEvent, atLeastOnce()).replyModal(modalArgumentCaptor.capture());
        List<Modal> actual = modalArgumentCaptor.getAllValues();
        assertEquals(1, actual.size());
        assertEquals(PLUS_ONE_ID, actual.get(0).getId());
        assertEquals(PLUS_ONE, actual.get(0).getTitle());
        List<LayoutComponent> components = actual.get(0).getComponents();
        assertEquals(1, components.size());
        assertEquals(1, components.get(0).getActionComponents().size());
        TextInput textInput = (TextInput) components.get(0).getComponents().get(0);
        assertEquals(PLUS_ONE_ID, textInput.getId());
        assertEquals(PLUS_ONE, textInput.getLabel());
        assertEquals(MODAL_PLACEHOLDER, textInput.getPlaceHolder());
    }

    private record Result(Clock clock, MetricsService metricsService, EventRepository eventRepository,
                          ButtonInteractionEvent buttonInteractionEvent, ModalCallbackAction modalCallbackAction,
                          EmbedService embedService) {
    }
}