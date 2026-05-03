package dev.tylercash.event.db.repository;

import dev.tylercash.event.discord.model.DiscordUserCache;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DiscordUserCacheRepository extends JpaRepository<DiscordUserCache, String> {
    List<DiscordUserCache> findAllBySnowflakeIn(Collection<String> snowflakes);
}
