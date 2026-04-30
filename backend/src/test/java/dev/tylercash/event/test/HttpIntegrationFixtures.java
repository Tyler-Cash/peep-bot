package dev.tylercash.event.test;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.event.AttendanceService;
import dev.tylercash.event.event.model.AttendanceStatus;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;

@TestComponent
@RequiredArgsConstructor
public class HttpIntegrationFixtures {

    private final DiscordUserCacheService discordUserCacheService;
    private final AttendanceService attendanceService;
    private final EventRepository eventRepository;

    private final AtomicLong messageIdCounter = new AtomicLong(20_000);

    /** Register a user as a member of a guild (populates discord_user_cache + discord_user_guild). */
    public void registerMember(String snowflake, long guildId, String displayName, String username) {
        discordUserCacheService.registerIfMissing(snowflake, displayName, username, guildId);
    }

    /** Seed a PLANNED event in the given guild, returns its UUID. */
    public UUID seedEvent(long guildId, String creatorSnowflake, String name) {
        long id = messageIdCounter.incrementAndGet();
        Event event = new Event(
                id, guildId, id, name, creatorSnowflake, ZonedDateTime.now().plusDays(1), "test event description");
        event.setState(EventState.PLANNED);
        Event saved = eventRepository.save(event);
        attendanceService.recordAttendance(saved.getId(), creatorSnowflake, null, AttendanceStatus.ACCEPTED, null);
        return saved.getId();
    }
}
