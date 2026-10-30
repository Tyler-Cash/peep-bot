package dev.tylercash.event.tarot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "tarot_draw")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TarotDraw {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "guild_id", nullable = false)
    private long guildId;

    @Column(name = "user_snowflake", nullable = false)
    private long userSnowflake;

    /** Calendar date in the guild's timezone at draw time; unique per (guild, user). */
    @Column(name = "draw_date", nullable = false)
    private LocalDate drawDate;

    @Column(name = "card_id", nullable = false)
    private String cardId;

    @Column(name = "reversed", nullable = false)
    private boolean reversed;

    /** Which of the card's readings (for its orientation) this draw showed. */
    @Column(name = "reading_index", nullable = false)
    private int readingIndex;

    @Column(name = "drawn_at", nullable = false)
    private Instant drawnAt;
}
