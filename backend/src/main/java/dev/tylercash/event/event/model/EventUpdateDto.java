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

    @Size(min = EventDto.NAME_SIZE_MIN, message = EventDto.NAME_SIZE_ERROR)
    @Size(max = EventDto.NAME_SIZE_MAX, message = EventDto.NAME_SIZE_ERROR)
    private String name;

    @Size(max = 3800, message = "Please make this less than 3800 characters long")
    private String description = "";

    @FutureOrPresent(message = "The event can only be organized in the future.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private ZonedDateTime dateTime;

    private Set<String> accepted;
}
