package dev.tylercash.event.discord;

import java.util.Arrays;

/**
 * Discord permissions the bot actually exercises. Add an enum entry when you
 * reach for a new permission in code; {@link #REQUIRED} is derived from
 * {@link BotPermission#values()} so every existing deployment picks up the
 * change without an env var update, and the install-URL endpoint surfaces the
 * new entry to the frontend modal automatically.
 *
 * @see <a href="https://discord.com/developers/docs/topics/permissions">Discord permission flags</a>
 */
public final class BotPermissions {
    private BotPermissions() {}

    public static final long REQUIRED =
            Arrays.stream(BotPermission.values()).mapToLong(BotPermission::bit).reduce(0L, (a, b) -> a | b);
}
