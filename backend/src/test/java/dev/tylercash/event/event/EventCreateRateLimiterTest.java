package dev.tylercash.event.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.tylercash.event.discord.Guild;
import dev.tylercash.event.discord.GuildRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EventCreateRateLimiterTest {

    private static EventCreateRateLimitConfiguration cfg(int perGuildPerHour) {
        EventCreateRateLimitConfiguration c = new EventCreateRateLimitConfiguration();
        c.setPerGuildPerHour(perGuildPerHour);
        return c;
    }

    private static GuildRepository repoWithLimit(long guildId, Integer perHour) {
        GuildRepository repo = mock(GuildRepository.class);
        Guild g = Guild.withDefaults(guildId);
        g.setEventCreateRateLimitPerHour(perHour);
        when(repo.findById(guildId)).thenReturn(Optional.of(g));
        return repo;
    }

    @Test
    void usesConfigDefaultWhenGuildHasNoOverride() {
        EventCreateRateLimiter limiter = new EventCreateRateLimiter(cfg(3), repoWithLimit(1L, null));

        assertThat(limiter.tryAcquire(1L).ok()).isTrue();
        assertThat(limiter.tryAcquire(1L).ok()).isTrue();
        assertThat(limiter.tryAcquire(1L).ok()).isTrue();
        assertThat(limiter.tryAcquire(1L).ok()).isFalse();
    }

    @Test
    void usesPerGuildOverrideWhenSet() {
        EventCreateRateLimiter limiter = new EventCreateRateLimiter(cfg(100), repoWithLimit(1L, 2));

        assertThat(limiter.tryAcquire(1L).ok()).isTrue();
        assertThat(limiter.tryAcquire(1L).ok()).isTrue();
        assertThat(limiter.tryAcquire(1L).ok()).isFalse();
    }

    @Test
    void guildBucketsAreIndependent() {
        GuildRepository repo = mock(GuildRepository.class);
        Guild g1 = Guild.withDefaults(1L);
        g1.setEventCreateRateLimitPerHour(1);
        Guild g2 = Guild.withDefaults(2L);
        g2.setEventCreateRateLimitPerHour(1);
        when(repo.findById(1L)).thenReturn(Optional.of(g1));
        when(repo.findById(2L)).thenReturn(Optional.of(g2));

        EventCreateRateLimiter limiter = new EventCreateRateLimiter(cfg(100), repo);

        assertThat(limiter.tryAcquire(1L).ok()).isTrue();
        assertThat(limiter.tryAcquire(1L).ok()).isFalse();
        assertThat(limiter.tryAcquire(2L).ok()).isTrue();
    }

    @Test
    void invalidateForcesNewCapacityOnNextAcquire() {
        GuildRepository repo = mock(GuildRepository.class);
        Guild g = Guild.withDefaults(1L);
        g.setEventCreateRateLimitPerHour(1);
        when(repo.findById(1L)).thenReturn(Optional.of(g));

        EventCreateRateLimiter limiter = new EventCreateRateLimiter(cfg(100), repo);

        assertThat(limiter.tryAcquire(1L).ok()).isTrue();
        assertThat(limiter.tryAcquire(1L).ok()).isFalse();

        g.setEventCreateRateLimitPerHour(5);
        limiter.invalidate(1L);

        assertThat(limiter.tryAcquire(1L).ok()).isTrue();
    }
}
