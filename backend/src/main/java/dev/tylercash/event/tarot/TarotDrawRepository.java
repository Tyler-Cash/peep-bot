package dev.tylercash.event.tarot;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TarotDrawRepository extends JpaRepository<TarotDraw, UUID> {

    Optional<TarotDraw> findByGuildIdAndUserSnowflakeAndDrawDate(long guildId, long userSnowflake, LocalDate drawDate);

    long countByGuildIdAndDrawDateGreaterThanEqual(long guildId, LocalDate since);
}
