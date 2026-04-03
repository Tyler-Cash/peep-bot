package dev.tylercash.event.event.model;

import dev.tylercash.event.event.model.converter.SetOfNotificationsConverter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

@Data
@Table
@Schema
@Entity
@NoArgsConstructor
@AllArgsConstructor
public class Event {
    @Id
    @GeneratedValue
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private UUID id;

    @NotNull
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private long messageId;

    @NotNull
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private long serverId;

    @NotNull
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private long channelId;

    @NotNull
    private String name;

    @NotNull
    private String creator;

    private String description = "";
    private String location = "";
    private Integer capacity = 0;
    private Integer cost = 0;

    @Transient
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private Set<Attendee> accepted = new HashSet<>();

    @Transient
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private Set<Attendee> declined = new HashSet<>();

    @Transient
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private Set<Attendee> maybe = new HashSet<>();

    @Convert(converter = SetOfNotificationsConverter.class)
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private Set<Notification> notifications = new HashSet<>();

    @Enumerated(EnumType.STRING)
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private EventState state = EventState.CREATED;

    @NotNull
    @Column(name = "date_time")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private ZonedDateTime dateTime;

    private String immichAlbumId;
    private String immichShareKey;

    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private Long acceptedRoleId;

    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private Long declinedRoleId;

    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private Long maybeRoleId;

    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private Long privateChannelId;

    // Request only flag, do not persist
    @Transient
    private boolean notifyOnCreate = true;

    // Resolved creator display name for rendering
    @Transient
    private String creatorDisplayName;

    public Event(
            long messageId,
            long serverId,
            long channelId,
            String name,
            String creator,
            ZonedDateTime dateTime,
            String description) {
        super();
        this.messageId = messageId;
        this.serverId = serverId;
        this.channelId = channelId;
        this.name = name;
        this.creator = creator;
        this.description = description;
        this.dateTime = dateTime;
    }

    public Event(EventDto event, String creator) {
        super();
        this.name = event.getName();
        this.creator = creator;
        this.description = event.getDescription();
        this.location = event.getLocation();
        this.capacity = event.getCapacity();
        this.cost = event.getCost();
        this.dateTime = event.getDateTime();
        // map transient notify flag (default true if null)
        this.notifyOnCreate = event.getNotifyOnCreate() == null || event.getNotifyOnCreate();
    }
}
