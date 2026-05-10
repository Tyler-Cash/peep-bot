package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GuildDefaultsTest {
    @Test
    void withDefaultsSetsTfnswDisabled() {
        Guild g = Guild.withDefaults(123L);
        assertThat(g.isTfnswEnabled()).isFalse();
    }
}
