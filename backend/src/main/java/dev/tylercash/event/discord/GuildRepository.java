package dev.tylercash.event.discord;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuildRepository extends JpaRepository<Guild, Long> {
    List<Guild> findAllByActiveTrue();
}
