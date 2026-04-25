package dev.tylercash.event.db.repository;

import dev.tylercash.event.discord.model.DiscordUserCache;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DiscordUserCacheRepository extends JpaRepository<DiscordUserCache, String> {
    List<DiscordUserCache> findAllBySnowflakeIn(Collection<String> snowflakes);

    List<DiscordUserCache> findAllByUpdatedAtBefore(Instant cutoff);

    @org.springframework.data.jpa.repository.Query(
            value =
                    """
            SELECT EXISTS (
                SELECT 1 FROM discord_user_guild m1
                JOIN discord_user_guild m2 ON m1.guild_id = m2.guild_id
                WHERE m1.snowflake = :u1 AND m2.snowflake = :u2
            )
            """,
            nativeQuery = true)
    boolean haveSharedGuild(
            @org.springframework.data.repository.query.Param("u1") String u1,
            @org.springframework.data.repository.query.Param("u2") String u2);
}
