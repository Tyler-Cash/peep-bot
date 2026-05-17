package dev.tylercash.event.discord.listener;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ListenerNoCompleteCallsTest {

    @Test
    void noCompleteCallsInListenerSources() throws IOException {
        Path listenerDir = Path.of("src/main/java/dev/tylercash/event/discord/listener");
        assertThat(listenerDir).exists();

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(listenerDir)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    List<String> lines = Files.readAllLines(p);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        if (line.contains(".complete(")) {
                            offenders.add(p.getFileName() + ":" + (i + 1) + " — " + line.trim());
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        assertThat(offenders)
                .as(
                        "JDA RestAction.complete() blocks the WebSocket read thread inside listeners; "
                                + "use .queue() or offload to discordListenerExecutor instead. Offenders:\n%s",
                        String.join("\n", offenders))
                .isEmpty();
    }
}
