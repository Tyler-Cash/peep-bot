package dev.tylercash.event.db.repository;

import dev.tylercash.event.discord.model.GuildMember;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
