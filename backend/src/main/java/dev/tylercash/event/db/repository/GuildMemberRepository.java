package dev.tylercash.event.db.repository;

import dev.tylercash.event.discord.model.GuildMember;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface GuildMemberRepository extends JpaRepository<GuildMember, GuildMember.PK> {

    Optional<GuildMember> findByGuildIdAndSnowflake(long guildId, String snowflake);

    List<GuildMember> findAllByGuildIdAndSnowflakeIn(long guildId, Collection<String> snowflakes);

    List<GuildMember> findAllByGuildId(long guildId);

    @Query(
            value =
                    """
            SELECT EXISTS (
                SELECT 1 FROM guild_member m1
                JOIN guild_member m2 ON m1.guild_id = m2.guild_id
                WHERE m1.snowflake = :u1 AND m2.snowflake = :u2
            )
            """,
            nativeQuery = true)
    boolean haveSharedGuild(@Param("u1") String u1, @Param("u2") String u2);

    @Query(
            """
            SELECT COUNT(gm) > 0 FROM GuildMember gm
            WHERE gm.snowflake = :snowflake AND gm.guildId = :guildId
            """)
    boolean isUserInGuild(@Param("snowflake") String snowflake, @Param("guildId") long guildId);

    @Query("SELECT gm.guildId FROM GuildMember gm WHERE gm.snowflake = :snowflake")
    List<Long> findGuildIdsBySnowflake(@Param("snowflake") String snowflake);

    @Modifying
    @Query(
            """
            DELETE FROM GuildMember gm
            WHERE gm.guildId = :guildId AND gm.snowflake NOT IN :snowflakes
            """)
    int deleteByGuildIdAndSnowflakeNotIn(
            @Param("guildId") long guildId, @Param("snowflakes") Collection<String> snowflakes);

    /**
     * Returns (guild_id, snowflake) pairs that need a Discord cache refresh — either no
     * guild_member row exists yet, or the row's updated_at is older than {@code staleCutoff}.
     * Pairs are derived from event.creator and attendance.snowflake within active guilds.
     * Results are ordered missing-first then oldest-first and capped at {@code limit}.
     */
    @Query(
            value =
                    """
            WITH pairs AS (
                    SELECT DISTINCT e.server_id AS guild_id, a.snowflake
                    FROM attendance a
                    JOIN event e ON e.id = a.event_id
                    WHERE a.snowflake IS NOT NULL
                UNION
                    SELECT DISTINCT e.server_id AS guild_id, e.creator AS snowflake
                    FROM event e
                    WHERE e.creator IS NOT NULL
            )
            SELECT p.guild_id AS guildId, p.snowflake AS snowflake
            FROM pairs p
            JOIN guild g ON g.guild_id = p.guild_id AND g.active = true
            LEFT JOIN guild_member gm ON gm.guild_id = p.guild_id AND gm.snowflake = p.snowflake
            WHERE gm.snowflake IS NULL OR gm.updated_at < :staleCutoff
            ORDER BY gm.updated_at NULLS FIRST
            LIMIT :limit
            """,
            nativeQuery = true)
    List<StaleMemberRef> findStaleOrMissing(
            @Param("staleCutoff") java.time.Instant staleCutoff, @Param("limit") int limit);

    /** Projection for {@link #findStaleOrMissing}. */
    interface StaleMemberRef {
        long getGuildId();

        String getSnowflake();
    }
}
