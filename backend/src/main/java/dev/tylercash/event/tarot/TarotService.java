package dev.tylercash.event.tarot;

import dev.tylercash.event.discord.Guild;
import dev.tylercash.event.discord.GuildRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class TarotService {

    static final ZoneId DEFAULT_ZONE = ZoneId.of("Australia/Sydney");

    private final TarotDeck deck;
    private final TarotDrawRepository drawRepository;
    private final TarotChannelRepository channelRepository;
    private final GuildRepository guildRepository;
    private final Clock clock;
    private final RandomGenerator random = new SecureRandom();

    public TarotService(
            TarotDeck deck,
            TarotDrawRepository drawRepository,
            TarotChannelRepository channelRepository,
            GuildRepository guildRepository,
            Clock clock) {
        this.deck = deck;
        this.drawRepository = drawRepository;
        this.channelRepository = channelRepository;
        this.guildRepository = guildRepository;
        this.clock = clock;
    }

    public record DrawResult(TarotDraw draw, TarotCard card, boolean alreadyDrawn, Instant nextReset) {
        public String reading() {
            return card.reading(draw.isReversed(), draw.getReadingIndex());
        }
    }

    /** The user's draw for today in the guild's timezone, if they've made one. */
    public Optional<DrawResult> todaysDraw(long guildId, long userSnowflake) {
        ZoneId zone = zoneFor(guildId);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        return drawRepository
                .findByGuildIdAndUserSnowflakeAndDrawDate(guildId, userSnowflake, today)
                .map(d -> toResult(d, true, zone));
    }

    /**
     * Draws today's card, or returns the existing one if the user already drew today. The
     * (guild, user, draw_date) unique constraint is the real daily limit; a concurrent second
     * draw loses the insert and gets the winner's card back.
     */
    public DrawResult drawToday(long guildId, long userSnowflake) {
        Optional<DrawResult> existing = todaysDraw(guildId, userSnowflake);
        if (existing.isPresent()) return existing.get();

        ZoneId zone = zoneFor(guildId);
        Instant now = clock.instant();
        TarotCard card = deck.random(random);
        boolean reversed = random.nextBoolean();
        TarotDraw draw = new TarotDraw(
                UUID.randomUUID(),
                guildId,
                userSnowflake,
                LocalDate.ofInstant(now, zone),
                card.id(),
                reversed,
                random.nextInt(card.readings(reversed).size()),
                now);
        try {
            drawRepository.saveAndFlush(draw);
            return toResult(draw, false, zone);
        } catch (DataIntegrityViolationException e) {
            log.debug("Concurrent tarot draw for user {} in guild {}; returning existing", userSnowflake, guildId);
            return todaysDraw(guildId, userSnowflake).orElseThrow(() -> e);
        }
    }

    public boolean isChannelAllowed(long guildId, long channelId) {
        List<Long> allowed = allowedChannelIds(guildId);
        return allowed.isEmpty() || allowed.contains(channelId);
    }

    /** Empty means every channel is allowed. */
    public List<Long> allowedChannelIds(long guildId) {
        return channelRepository.findByGuildId(guildId).stream()
                .map(TarotChannel::getChannelId)
                .sorted()
                .toList();
    }

    @Transactional
    public void replaceAllowedChannels(long guildId, Collection<Long> channelIds) {
        channelRepository.deleteByGuildId(guildId);
        channelRepository.saveAll(channelIds.stream()
                .distinct()
                .map(id -> new TarotChannel(guildId, id))
                .toList());
    }

    public void forgetChannel(long channelId) {
        int removed = channelRepository.deleteByChannelId(channelId);
        if (removed > 0) log.info("Removed deleted channel {} from tarot allowlist", channelId);
    }

    public long drawsSince(long guildId, int days) {
        LocalDate since = LocalDate.now(clock.withZone(zoneFor(guildId))).minusDays(days - 1L);
        return drawRepository.countByGuildIdAndDrawDateGreaterThanEqual(guildId, since);
    }

    ZoneId zoneFor(long guildId) {
        return guildRepository
                .findById(guildId)
                .map(Guild::getTimezone)
                .map(TarotService::parseZone)
                .orElse(DEFAULT_ZONE);
    }

    private static ZoneId parseZone(String tz) {
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException | NullPointerException e) {
            log.warn("Invalid guild timezone '{}', falling back to {}", tz, DEFAULT_ZONE);
            return DEFAULT_ZONE;
        }
    }

    private DrawResult toResult(TarotDraw draw, boolean alreadyDrawn, ZoneId zone) {
        TarotCard card = deck.byId(draw.getCardId())
                .orElseThrow(() -> new IllegalStateException("Unknown tarot card " + draw.getCardId()));
        Instant nextReset = draw.getDrawDate().plusDays(1).atStartOfDay(zone).toInstant();
        return new DrawResult(draw, card, alreadyDrawn, nextReset);
    }
}
