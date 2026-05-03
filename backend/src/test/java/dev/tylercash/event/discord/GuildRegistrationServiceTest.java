package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class GuildRegistrationServiceTest {

    @Test
    void onboardCreatesRowWhenMissing() {
        GuildRepository repo = mock(GuildRepository.class);
        when(repo.findById(42L)).thenReturn(Optional.empty());
        DiscordInitializationService init = mock(DiscordInitializationService.class);
        GuildEmojiResolver emoji = mock(GuildEmojiResolver.class);

        net.dv8tion.jda.api.entities.Guild jdaGuild = mock(net.dv8tion.jda.api.entities.Guild.class);
        when(jdaGuild.getIdLong()).thenReturn(42L);
        when(jdaGuild.getName()).thenReturn("Test");

        GuildRegistrationService svc = new GuildRegistrationService(repo, init, emoji);
        svc.onboard(jdaGuild);

        verify(repo).save(argThat(g -> g.getGuildId() == 42L && g.isActive() && "events".equals(g.getEventsRole())));
        verify(init).initialise(eq(jdaGuild), any(Guild.class));
        verify(emoji).resolve(eq(jdaGuild), any(Guild.class));
    }

    @Test
    void onboardReactivatesDormantRow() {
        Guild row = Guild.withDefaults(7L);
        row.setActive(false);

        GuildRepository repo = mock(GuildRepository.class);
        when(repo.findById(7L)).thenReturn(Optional.of(row));
        DiscordInitializationService init = mock(DiscordInitializationService.class);
        GuildEmojiResolver emoji = mock(GuildEmojiResolver.class);

        net.dv8tion.jda.api.entities.Guild jdaGuild = mock(net.dv8tion.jda.api.entities.Guild.class);
        when(jdaGuild.getIdLong()).thenReturn(7L);

        GuildRegistrationService svc = new GuildRegistrationService(repo, init, emoji);
        svc.onboard(jdaGuild);

        verify(repo).save(argThat(g -> g.getGuildId() == 7L && g.isActive()));
    }

    @Test
    void deactivateMarksRowInactiveAndEvictsEmoji() {
        Guild row = Guild.withDefaults(99L);
        GuildRepository repo = mock(GuildRepository.class);
        when(repo.findById(99L)).thenReturn(Optional.of(row));
        DiscordInitializationService init = mock(DiscordInitializationService.class);
        GuildEmojiResolver emoji = mock(GuildEmojiResolver.class);

        GuildRegistrationService svc = new GuildRegistrationService(repo, init, emoji);
        svc.deactivate(99L);

        verify(repo).save(argThat(g -> !g.isActive()));
        verify(emoji).evict(99L);

        // Add a tiny redundant assertion so JUnit doesn't complain about no assertions
        assertThat(true).isTrue();
    }
}
