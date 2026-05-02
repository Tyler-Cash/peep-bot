package dev.tylercash.event.discord;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "guild")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Guild {

    @Id
    @Column(name = "guild_id")
    private Long guildId;

    @Column(name = "events_role", nullable = false)
    private String eventsRole;

    @Column(name = "organiser_role", nullable = false)
    private String organiserRole;

    @Column(name = "separator_channel")
    private String separatorChannel;

    @Column(name = "emoji_accepted", nullable = false)
    private String emojiAccepted;

    @Column(name = "emoji_declined", nullable = false)
    private String emojiDeclined;

    @Column(name = "emoji_maybe", nullable = false)
    private String emojiMaybe;

    @Column(name = "primary_location_place_id")
    private String primaryLocationPlaceId;

    @Column(name = "primary_location_name")
    private String primaryLocationName;

    @Column(name = "primary_location_lat")
    private Double primaryLocationLat;

    @Column(name = "primary_location_lng")
    private Double primaryLocationLng;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "immich_enabled", nullable = false)
    private boolean immichEnabled;

    @Column(name = "google_autocomplete_enabled", nullable = false)
    private boolean googleAutocompleteEnabled;

    @Column(name = "rewind_enabled", nullable = false)
    private boolean rewindEnabled;

    public static Guild withDefaults(long guildId) {
        Guild g = new Guild();
        g.setGuildId(guildId);
        g.setEventsRole("events");
        g.setOrganiserRole("event-organiser");
        g.setEmojiAccepted("✅");
        g.setEmojiDeclined("❌");
        g.setEmojiMaybe("❓");
        g.setJoinedAt(Instant.now());
        g.setActive(true);
        g.setImmichEnabled(false);
        g.setGoogleAutocompleteEnabled(false);
        g.setRewindEnabled(false);
        return g;
    }
}
