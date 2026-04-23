package dev.tylercash.event.discord.listener;

import static dev.tylercash.event.discord.listener.ModalInteractionListener.PLUS_ONE_ID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.interactions.modals.ModalInteraction;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.requests.restaction.interactions.MessageEditCallbackAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ModalInteractionListenerTest {
    private static final long CHANNEL_ID = 777L;
    private static final String USER_ID = "42";
    private static final String USER_NICKNAME = "tester";

    private Clock clock;
    private EventRepository eventRepository;
    private EmbedService embedService;
    private EventService eventService;
    private AttendanceService attendanceService;
    private DiscordUserCacheService discordUserCacheService;
    private ObjectProvider<EventService> eventServiceProvider;

    private ModalInteractionListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-05-01T12:00:00Z"), ZoneId.of("UTC"));
        eventRepository = mock(EventRepository.class);
        embedService = mock(EmbedService.class);
        eventService = mock(EventService.class);
        attendanceService = mock(AttendanceService.class);
        discordUserCacheService = mock(DiscordUserCacheService.class);
        eventServiceProvider = mock(ObjectProvider.class);
        when(eventServiceProvider.getObject()).thenReturn(eventService);

        listener = new ModalInteractionListener(
                clock,
                ObservationRegistry.NOOP,
                eventRepository,
                embedService,
                eventServiceProvider,
                attendanceService,
                discordUserCacheService);
    }

    private ModalInteractionEvent modalEvent(String modalId, String plusOneName) {
        ModalInteractionEvent evt = mock(ModalInteractionEvent.class);
        ModalInteraction interaction = mock(ModalInteraction.class);
        when(evt.getInteraction()).thenReturn(interaction);
        when(interaction.getModalId()).thenReturn(modalId);

        MessageChannelUnion channel = mock(MessageChannelUnion.class);
        when(channel.getIdLong()).thenReturn(CHANNEL_ID);
        when(evt.getChannel()).thenReturn(channel);

        User user = mock(User.class);
        when(user.getId()).thenReturn(USER_ID);
        when(user.getEffectiveName()).thenReturn(USER_NICKNAME);
        when(evt.getUser()).thenReturn(user);

        Member member = mock(Member.class);
        when(member.getNickname()).thenReturn(USER_NICKNAME);
        when(member.getEffectiveName()).thenReturn(USER_NICKNAME);
        when(evt.getMember()).thenReturn(member);

        if (plusOneName != null) {
            ModalMapping mapping = mock(ModalMapping.class);
            when(mapping.getAsString()).thenReturn(plusOneName);
            when(interaction.getValues()).thenReturn(List.of(mapping));
        }

        return evt;
    }

    @Test
    @DisplayName("records a +1 attendance, upserts the owner, and edits the message embed on the happy path")
    void happyPath_addsPlusOne() {
        ModalInteractionEvent evt = modalEvent(PLUS_ONE_ID, "Guest Name");

        Event event = new Event();
        event.setId(UUID.randomUUID());
        event.setName("Party");
        event.setCreator("host");
        event.setDateTime(ZonedDateTime.parse("2026-06-01T18:00:00Z"));
        when(eventRepository.findByChannelId(CHANNEL_ID)).thenReturn(event);
        when(eventService.isCompleted(event)).thenReturn(false);
        when(embedService.getMessage(event, clock)).thenReturn(List.of(mock(MessageEmbed.class)));
        when(evt.editMessageEmbeds(embedService.getMessage(event, clock)))
                .thenReturn(mock(MessageEditCallbackAction.class));

        listener.onModalInteraction(evt);

        verify(discordUserCacheService).upsertUser(USER_ID, USER_NICKNAME);
        verify(attendanceService)
                .recordAttendance(event.getId(), null, "[+1] Guest Name", AttendanceStatus.ACCEPTED, USER_ID);
        verify(eventService).populateAttendance(event);
        verify(evt).editMessageEmbeds(embedService.getMessage(event, clock));
    }

    @Test
    @DisplayName("replies with an ephemeral locked message when no event is found for the channel")
    void eventNotFound_repliesLocked() {
        ModalInteractionEvent evt = modalEvent(PLUS_ONE_ID, "ignored");
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(evt.reply(any(String.class))).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);
        when(eventRepository.findByChannelId(CHANNEL_ID)).thenReturn(null);

        listener.onModalInteraction(evt);

        verify(evt).reply("Attendance is locked for this event.");
        verify(reply).setEphemeral(true);
        verify(reply).queue();
        verifyNoInteractions(attendanceService);
    }

    @Test
    @DisplayName("replies with an ephemeral locked message when the event is already completed")
    void eventCompleted_repliesLocked() {
        ModalInteractionEvent evt = modalEvent(PLUS_ONE_ID, "ignored");
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(evt.reply(any(String.class))).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);

        Event event = new Event();
        event.setId(UUID.randomUUID());
        when(eventRepository.findByChannelId(CHANNEL_ID)).thenReturn(event);
        when(eventService.isCompleted(event)).thenReturn(true);

        listener.onModalInteraction(evt);

        verify(evt).reply("Attendance is locked for this event.");
        verify(reply).setEphemeral(true);
        verify(reply).queue();
        verifyNoInteractions(attendanceService);
        verify(eventService, never()).populateAttendance(any());
    }

    @Test
    @DisplayName("ignores modal interactions whose id is not the +1 modal")
    void unknownModalId_isIgnored() {
        ModalInteractionEvent evt = modalEvent("some-other-modal", null);

        listener.onModalInteraction(evt);

        verifyNoInteractions(attendanceService);
        verifyNoInteractions(discordUserCacheService);
        verifyNoInteractions(embedService);
        verify(eventRepository, never()).findByChannelId(anyLong());
        verify(eventService, never()).isCompleted(any());
    }
}
