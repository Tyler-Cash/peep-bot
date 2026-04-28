package dev.tylercash.event.event.model;

import jakarta.validation.constraints.*;
import java.time.ZonedDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventDto {
    public static final int NAME_SIZE_MIN = 4;
    public static final int NAME_SIZE_MAX = 80;
    public static final String NAME_SIZE_ERROR =
            "Name should be between " + NAME_SIZE_MIN + " and " + NAME_SIZE_MAX + " characters long";
    private UUID id;

    @NotNull(message = "Guild ID is required.")
    private long guildId;

    @NotBlank(message = "Name is required.")
    @Size(min = NAME_SIZE_MIN, message = NAME_SIZE_ERROR)
    @Size(max = NAME_SIZE_MAX, message = NAME_SIZE_ERROR)
    private String name;

    @Size(max = 3800, message = "Please make this less than 3800 characters long")
    private String description = "";

    private String location = "";
    private String locationPlaceId;

    @PositiveOrZero(message = "Capacity must be positive.")
    private Integer capacity = 0;

    @PositiveOrZero(message = "Cost must be positive.")
    private Integer cost = 0;

    @NotNull(message = "A date and time is required.")
    @FutureOrPresent(message = "The event can only be organized in the future.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private ZonedDateTime dateTime;

    private String host;
    private String hostUsername;
    private String hostAvatarUrl;
    private String category = "unknown";
    private String state;
    private String displayState;

    private String channelId;
    private String messageId;

    // Transient request-only flag: whether to notify people when creating the event. Defaults to true.
    private Boolean notifyOnCreate = true;

    public EventDto(Event event) {
        this.id = event.getId();
        this.guildId = event.getServerId();
        this.name = event.getName();
        this.description = event.getDescription();
        this.location = event.getLocation();
        this.locationPlaceId = event.getLocationPlaceId();
        this.capacity = event.getCapacity();
        this.cost = event.getCost();
        this.dateTime = event.getDateTime();
        this.state = event.getState() != null ? event.getState().name() : null;
        this.displayState = toDisplayState(event.getState());
        String creator = event.getCreator();
        this.host = event.getCreatorDisplayName(); // transient, may be null
        this.hostAvatarUrl = (creator != null && !creator.isBlank()) ? "/api/avatar/" + creator : null;
        this.channelId = String.valueOf(event.getChannelId());
        this.messageId = String.valueOf(event.getMessageId());
        // Do not expose notifyOnCreate from entity; it's request-only and not persisted
    }

    private static String toDisplayState(EventState state) {
        if (state == null) return null;
        return switch (state) {
            case CREATED, INIT_CHANNEL, INIT_ROLES, CLASSIFY -> "creating";
            case PLANNED, PRE_NOTIFIED -> "planned";
            case POST_ALBUM_READY, POST_ALBUM_SHARED, POST_COMPLETED, ARCHIVED -> "archived";
            case CANCELLED -> "cancelled";
            case DELETED -> "deleted";
        };
    }

    public EventDto(Event event, String hostDisplayName, String hostUsername) {
        this(event);
        this.host = hostDisplayName;
        this.hostUsername = hostUsername;
    }

    public EventDto(Event event, String hostDisplayName, String hostUsername, String category) {
        this(event, hostDisplayName, hostUsername);
        this.category = category == null || category.isBlank() ? "unknown" : category;
    }
}
