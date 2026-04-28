package dev.tylercash.event.db.repository;

import dev.tylercash.event.discord.model.DiscordGuildMember;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface DiscordGuildMemberRepository extends JpaRepository<DiscordGuildMember, DiscordGuildMember.PK> {

    Optional<DiscordGuildMember> findByGuildIdAndSnowflake(long guildId, String snowflake);

    List<DiscordGuildMember> findAllByGuildIdAndSnowflakeIn(long guildId, Collection<String> snowflakes);

    boolean existsByGuildIdAndSnowflake(long guildId, String snowflake);

    @Query("select m.guildId from DiscordGuildMember m where m.snowflake = :snowflake")
    List<Long> findGuildIdsBySnowflake(@Param("snowflake") String snowflake);

    @Query(
            """
            select case when count(m1) > 0 then true else false end
            from DiscordGuildMember m1, DiscordGuildMember m2
            where m1.guildId = m2.guildId
            and m1.snowflake = :u1
            and m2.snowflake = :u2
            """)
    boolean haveSharedGuild(@Param("u1") String u1, @Param("u2") String u2);
}
