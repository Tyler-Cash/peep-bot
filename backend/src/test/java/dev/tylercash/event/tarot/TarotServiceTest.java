package dev.tylercash.event.tarot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.tylercash.event.discord.Guild;
import dev.tylercash.event.discord.GuildRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;

class TarotServiceTest {

    private static final long GUILD = 1L;
    private static final long USER = 2L;
    // 23:59:59 on 20 Oct in Sydney (AEDT, UTC+11)
    private static final Instant BEFORE_SYDNEY_MIDNIGHT = Instant.parse("2026-10-20T12:59:59Z");
    private static final Instant SYDNEY_MIDNIGHT = Instant.parse("2026-10-20T13:00:00Z");

    private final TarotDeck deck = new TarotDeck(JsonMapper.builder().build());
    private TarotDrawRepository drawRepository;
    private TarotChannelRepository channelRepository;
    private GuildRepository guildRepository;

    @BeforeEach
    void setUp() {
        drawRepository = mock(TarotDrawRepository.class);
        channelRepository = mock(TarotChannelRepository.class);
        guildRepository = mock(GuildRepository.class);
        when(guildRepository.findById(GUILD)).thenReturn(Optional.of(Guild.withDefaults(GUILD)));
        when(drawRepository.findByGuildIdAndUserSnowflakeAndDrawDate(anyLong(), anyLong(), any()))
                .thenReturn(Optional.empty());
    }

    private TarotService serviceAt(Instant now) {
        return new TarotService(
                deck, drawRepository, channelRepository, guildRepository, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void drawDateUsesGuildTimezoneAndResetsAtLocalMidnight() {
        TarotService.DrawResult before = serviceAt(BEFORE_SYDNEY_MIDNIGHT).drawToday(GUILD, USER);
        assertThat(before.alreadyDrawn()).isFalse();
        assertThat(before.draw().getDrawDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(before.nextReset()).isEqualTo(SYDNEY_MIDNIGHT);

        TarotService.DrawResult after = serviceAt(SYDNEY_MIDNIGHT).drawToday(GUILD, USER);
        assertThat(after.draw().getDrawDate()).isEqualTo(LocalDate.of(2026, 10, 21));
    }

    @Test
    void persistsTheDrawnCard() {
        TarotService.DrawResult result = serviceAt(BEFORE_SYDNEY_MIDNIGHT).drawToday(GUILD, USER);

        ArgumentCaptor<TarotDraw> saved = ArgumentCaptor.forClass(TarotDraw.class);
        verify(drawRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getCardId()).isEqualTo(result.card().id());
        assertThat(saved.getValue().getGuildId()).isEqualTo(GUILD);
        assertThat(saved.getValue().getUserSnowflake()).isEqualTo(USER);
        assertThat(saved.getValue().getDrawnAt()).isEqualTo(BEFORE_SYDNEY_MIDNIGHT);
        assertThat(saved.getValue().getReadingIndex())
                .isBetween(0, result.card().readings(result.draw().isReversed()).size() - 1);
        assertThat(result.reading())
                .isEqualTo(result.card()
                        .readings(result.draw().isReversed())
                        .get(saved.getValue().getReadingIndex()));
    }

    @Test
    void secondDrawSameDayReturnsExistingCardWithoutSaving() {
        TarotDraw existing = draw("major_16", true, LocalDate.of(2026, 10, 20));
        when(drawRepository.findByGuildIdAndUserSnowflakeAndDrawDate(GUILD, USER, LocalDate.of(2026, 10, 20)))
                .thenReturn(Optional.of(existing));

        TarotService.DrawResult result = serviceAt(BEFORE_SYDNEY_MIDNIGHT).drawToday(GUILD, USER);

        assertThat(result.alreadyDrawn()).isTrue();
        assertThat(result.card().name()).isEqualTo("The Tower");
        assertThat(result.draw().isReversed()).isTrue();
        verify(drawRepository, never()).saveAndFlush(any());
    }

    @Test
    void concurrentDrawLosingTheInsertReturnsTheWinner() {
        LocalDate today = LocalDate.of(2026, 10, 20);
        TarotDraw winner = draw("major_19", false, today);
        when(drawRepository.findByGuildIdAndUserSnowflakeAndDrawDate(GUILD, USER, today))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(drawRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_tarot_draw_daily"));

        TarotService.DrawResult result = serviceAt(BEFORE_SYDNEY_MIDNIGHT).drawToday(GUILD, USER);

        assertThat(result.alreadyDrawn()).isTrue();
        assertThat(result.card().name()).isEqualTo("The Sun");
    }

    @Test
    void invalidGuildTimezoneFallsBackToSydney() {
        Guild guild = Guild.withDefaults(GUILD);
        guild.setTimezone("Not/AZone");
        when(guildRepository.findById(GUILD)).thenReturn(Optional.of(guild));

        TarotService.DrawResult result = serviceAt(BEFORE_SYDNEY_MIDNIGHT).drawToday(GUILD, USER);

        assertThat(result.nextReset()).isEqualTo(SYDNEY_MIDNIGHT);
    }

    @Test
    void guildTimezoneIsRespected() {
        Guild guild = Guild.withDefaults(GUILD);
        guild.setTimezone("UTC");
        when(guildRepository.findById(GUILD)).thenReturn(Optional.of(guild));

        TarotService.DrawResult result = serviceAt(BEFORE_SYDNEY_MIDNIGHT).drawToday(GUILD, USER);

        assertThat(result.draw().getDrawDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(result.nextReset()).isEqualTo(Instant.parse("2026-10-21T00:00:00Z"));
    }

    @Test
    void emptyAllowlistAllowsEveryChannel() {
        when(channelRepository.findByGuildId(GUILD)).thenReturn(List.of());

        assertThat(serviceAt(SYDNEY_MIDNIGHT).isChannelAllowed(GUILD, 123L)).isTrue();
    }

    @Test
    void nonEmptyAllowlistOnlyAllowsListedChannels() {
        when(channelRepository.findByGuildId(GUILD)).thenReturn(List.of(new TarotChannel(GUILD, 10L)));
        TarotService service = serviceAt(SYDNEY_MIDNIGHT);

        assertThat(service.isChannelAllowed(GUILD, 10L)).isTrue();
        assertThat(service.isChannelAllowed(GUILD, 11L)).isFalse();
    }

    private static TarotDraw draw(String cardId, boolean reversed, LocalDate date) {
        return new TarotDraw(UUID.randomUUID(), GUILD, USER, date, cardId, reversed, 0, BEFORE_SYDNEY_MIDNIGHT);
    }
}
