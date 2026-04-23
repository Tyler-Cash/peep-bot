package dev.tylercash.event.rewind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.tylercash.event.rewind.model.RewindStatsDto;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.user.OAuth2User;

class RewindControllerTest {
    private final RewindService rewindService = mock(RewindService.class);
    private final RewindController controller = new RewindController(rewindService);

    @Test
    @DisplayName("getGuildStats delegates to the service with the provided year")
    void getGuildStats_passesYear() {
        RewindStatsDto expected = mock(RewindStatsDto.class);
        when(rewindService.getGuildStats(2025)).thenReturn(expected);

        assertThat(controller.getGuildStats(2025)).isSameAs(expected);
    }

    @Test
    @DisplayName("getGuildStats forwards a null year when no year is provided")
    void getGuildStats_forwardsNullYear() {
        controller.getGuildStats(null);

        verify(rewindService).getGuildStats(null);
    }

    @Test
    @DisplayName("getMyStats extracts the 'id' attribute from the OAuth2User and forwards it as the snowflake")
    void getMyStats_extractsSnowflakeFromOauthPrincipal() {
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttribute("id")).thenReturn("snowflake-123");
        RewindStatsDto expected = mock(RewindStatsDto.class);
        when(rewindService.getUserStats("snowflake-123", 2024)).thenReturn(expected);

        assertThat(controller.getMyStats(principal, 2024)).isSameAs(expected);
    }

    @Test
    @DisplayName("getYears delegates to the service")
    void getYears_delegates() {
        when(rewindService.getYears()).thenReturn(List.of(2026, 2025, 2024));

        assertThat(controller.getYears()).containsExactly(2026, 2025, 2024);
    }
}
