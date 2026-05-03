package dev.tylercash.event.discord;

/**
 * Discord permissions the bot actually exercises. Add a bit when you reach for
 * a new permission in code; the install URL endpoint reads {@link #REQUIRED}
 * so every existing deployment picks up the change without an env var update.
 *
 * @see <a href="https://discord.com/developers/docs/topics/permissions">Discord permission flags</a>
 */
public final class BotPermissions {
    private BotPermissions() {}

    // Bit flags — keep one per line and document which bot operation needs it.
    private static final long VIEW_CHANNELS = 0x0000_0400L; // every channel/category lookup
    private static final long MANAGE_CHANNELS = 0x0000_0010L; // create/delete event channels, sort, archive
    private static final long MANAGE_ROLES = 0x1000_0000L; // per-event Accepted/Declined/Maybe roles
    private static final long SEND_MESSAGES = 0x0000_0800L; // event message + RSVP/cancel notifications
    private static final long EMBED_LINKS = 0x0000_4000L; // event embed body
    private static final long ATTACH_FILES = 0x0000_8000L; // contract chart PNGs
    private static final long PIN_MESSAGES = 0x0008_0000_0000_0000L; // bit 51 — Discord 2025+; pin event messages
    private static final long READ_MESSAGE_HISTORY = 0x0001_0000L; // scanning existing channels for sort
    private static final long USE_EXTERNAL_EMOJIS = 0x0004_0000L; // custom guild emoji in buttons
    private static final long MENTION_EVERYONE = 0x0002_0000L; // ping the @events role

    public static final long REQUIRED = VIEW_CHANNELS
            | MANAGE_CHANNELS
            | MANAGE_ROLES
            | SEND_MESSAGES
            | EMBED_LINKS
            | ATTACH_FILES
            | PIN_MESSAGES
            | READ_MESSAGE_HISTORY
            | USE_EXTERNAL_EMOJIS
            | MENTION_EVERYONE;
}
