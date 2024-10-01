package dev.tylercash.event.discord;

import dev.tylercash.event.event.model.Event;
import lombok.AllArgsConstructor;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Collection;
import java.util.List;

@Service
@AllArgsConstructor
public class EmbedService {
    public Collection<MessageEmbed> getMessage(Event event, Clock clock) {
        return List.of(new EmbedRenderer(event, clock).getEmbedBuilder().build());
    }
}
