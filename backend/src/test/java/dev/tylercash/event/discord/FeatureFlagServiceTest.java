package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FeatureFlagServiceTest {

    private static final long GUILD_ID = 42L;

    @Mock
    private GuildRepository guildRepository;

    private FeatureFlagService service;

    @BeforeEach
    void setUp() {
        service = new FeatureFlagService(guildRepository);
    }

    private Guild guildWith(boolean immich, boolean google, boolean rewind) {
        Guild g = Guild.withDefaults(GUILD_ID);
        g.setImmichEnabled(immich);
        g.setGoogleAutocompleteEnabled(google);
        g.setRewindEnabled(rewind);
        return g;
    }

    @Test
    void immich_enabled_returnsTrue() {
        when(guildRepository.findById(GUILD_ID)).thenReturn(Optional.of(guildWith(true, false, false)));
        assertThat(service.isEnabled(GUILD_ID, Feature.IMMICH)).isTrue();
    }

    @Test
    void immich_disabled_returnsFalse() {
        when(guildRepository.findById(GUILD_ID)).thenReturn(Optional.of(guildWith(false, false, false)));
        assertThat(service.isEnabled(GUILD_ID, Feature.IMMICH)).isFalse();
    }

    @Test
    void googleAutocomplete_enabled_returnsTrue() {
        when(guildRepository.findById(GUILD_ID)).thenReturn(Optional.of(guildWith(false, true, false)));
        assertThat(service.isEnabled(GUILD_ID, Feature.GOOGLE_AUTOCOMPLETE)).isTrue();
    }

    @Test
    void googleAutocomplete_disabled_returnsFalse() {
        when(guildRepository.findById(GUILD_ID)).thenReturn(Optional.of(guildWith(false, false, false)));
        assertThat(service.isEnabled(GUILD_ID, Feature.GOOGLE_AUTOCOMPLETE)).isFalse();
    }

    @Test
    void rewind_enabled_returnsTrue() {
        when(guildRepository.findById(GUILD_ID)).thenReturn(Optional.of(guildWith(false, false, true)));
        assertThat(service.isEnabled(GUILD_ID, Feature.REWIND)).isTrue();
    }

    @Test
    void rewind_disabled_returnsFalse() {
        when(guildRepository.findById(GUILD_ID)).thenReturn(Optional.of(guildWith(false, false, false)));
        assertThat(service.isEnabled(GUILD_ID, Feature.REWIND)).isFalse();
    }

    @Test
    void missingGuild_returnsFalse() {
        when(guildRepository.findById(GUILD_ID)).thenReturn(Optional.empty());
        assertThat(service.isEnabled(GUILD_ID, Feature.IMMICH)).isFalse();
        assertThat(service.isEnabled(GUILD_ID, Feature.GOOGLE_AUTOCOMPLETE)).isFalse();
        assertThat(service.isEnabled(GUILD_ID, Feature.REWIND)).isFalse();
    }
}
