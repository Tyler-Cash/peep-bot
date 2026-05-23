package dev.tylercash.event.discord;

import java.util.List;
import java.util.Optional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuildRepository extends JpaRepository<Guild, Long> {

    // Guild rows are read on every authenticated request (feature flags, settings).
    // They change only via admin/bot-owner endpoints and the Discord onboard/deactivate
    // paths, all of which route through save() and trip the eviction below.
    // Guild has no relations, so caching the detached entity is safe.
    @Override
    @Cacheable(value = "guild")
    Optional<Guild> findById(Long id);

    @Override
    @CacheEvict(value = "guild", key = "#entity.guildId")
    <S extends Guild> S save(S entity);

    List<Guild> findAllByActiveTrue();
}
