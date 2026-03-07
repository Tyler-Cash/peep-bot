package dev.tylercash.event.event.model;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventUpdateDto {
    @NotNull(message = "Must provide ID of event to modify")
    private UUID id;

    @PositiveOrZero(message = "Capacity must be positive.")
    private Integer capacity = 0;

    @Size(min = 4, max = 80, message = "Name should be between 4 and 80 characters long")
    private String name;

    @Size(max = 3800, message = "Please make this less than 3800 characters long")
    private String description = "";

    @FutureOrPresent(message = "The event can only be organized in the future.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private ZonedDateTime dateTime;

    @Size(max = 50, message = "Cannot add more than 50 attendees at once")
    private Set<String> accepted;
}
