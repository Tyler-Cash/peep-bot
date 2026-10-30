package dev.tylercash.event.tarot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import dev.tylercash.event.test.TestIds;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class TarotRepositoryIntegrationTest extends AbstractHttpIntegrationTest {

    @Autowired
    TarotDrawRepository drawRepository;

    @Autowired
    TarotService tarotService;

    private void seedGuild(long guildId) {
        jdbc.execute("INSERT INTO guild (guild_id, events_role, organiser_role, emoji_accepted, emoji_declined,"
                + " emoji_maybe, joined_at, active, immich_enabled, google_autocomplete_enabled, rewind_enabled)"
                + " VALUES (" + guildId + ", 'events', 'event-organiser', '✅', '❌', '❓', NOW(), true, false,"
                + " false, false)");
    }

    @Test
    void uniqueConstraintAllowsOneDrawPerUserPerDay() {
        long guildId = TestIds.nextLong();
        long user = TestIds.nextLong();
        LocalDate day = LocalDate.of(2026, 10, 20);
        drawRepository.saveAndFlush(
                new TarotDraw(UUID.randomUUID(), guildId, user, day, "major_00", false, 0, Instant.now()));

        assertThatThrownBy(() -> drawRepository.saveAndFlush(
                        new TarotDraw(UUID.randomUUID(), guildId, user, day, "major_01", true, 0, Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Next day and other guilds are independent.
        drawRepository.saveAndFlush(
                new TarotDraw(UUID.randomUUID(), guildId, user, day.plusDays(1), "major_02", false, 0, Instant.now()));
        drawRepository.saveAndFlush(
                new TarotDraw(UUID.randomUUID(), TestIds.nextLong(), user, day, "major_03", false, 0, Instant.now()));
    }

    @Test
    void drawTodayIsIdempotentWithinADay() {
        long guildId = TestIds.nextLong();
        seedGuild(guildId);
        long user = TestIds.nextLong();

        TarotService.DrawResult first = tarotService.drawToday(guildId, user);
        TarotService.DrawResult second = tarotService.drawToday(guildId, user);

        assertThat(first.alreadyDrawn()).isFalse();
        assertThat(second.alreadyDrawn()).isTrue();
        assertThat(second.card()).isEqualTo(first.card());
        assertThat(tarotService.drawsSince(guildId, 7)).isEqualTo(1);
    }

    @Test
    void replaceAllowedChannelsOverwritesPreviousSet() {
        long guildId = TestIds.nextLong();
        seedGuild(guildId);
        long a = TestIds.nextLong();
        long b = TestIds.nextLong();
        long c = TestIds.nextLong();

        tarotService.replaceAllowedChannels(guildId, List.of(b, a, a));
        assertThat(tarotService.allowedChannelIds(guildId)).containsExactlyInAnyOrder(a, b);
        assertThat(tarotService.isChannelAllowed(guildId, c)).isFalse();

        tarotService.replaceAllowedChannels(guildId, List.of(c));
        assertThat(tarotService.allowedChannelIds(guildId)).containsExactly(c);

        tarotService.forgetChannel(c);
        assertThat(tarotService.allowedChannelIds(guildId)).isEmpty();
        assertThat(tarotService.isChannelAllowed(guildId, a)).isTrue();
    }
}
