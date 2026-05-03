package dev.tylercash.event.discord;

public enum BotPermission {
    VIEW_CHANNELS(0x0000_0400L, "View channels", "Look up channels and categories"),
    MANAGE_CHANNELS(0x0000_0010L, "Manage channels", "Create, sort, and archive event channels"),
    MANAGE_ROLES(0x1000_0000L, "Manage roles", "Per-event Accepted / Declined / Maybe roles"),
    SEND_MESSAGES(0x0000_0800L, "Send messages", "Event message and RSVP / cancel notifications"),
    EMBED_LINKS(0x0000_4000L, "Embed links", "Event embed body"),
    PIN_MESSAGES(0x0008_0000_0000_0000L, "Pin messages", "Pin event messages in their channel"),
    READ_MESSAGE_HISTORY(
            0x0001_0000L,
            "Read message history",
            "Find and delete Discord's \"pinned a message\" system notification in private event channels"),
    USE_EXTERNAL_EMOJIS(0x0004_0000L, "Use external emojis", "Custom guild emoji in buttons"),
    MENTION_EVERYONE(0x0002_0000L, "Mention everyone", "Ping the @events role");

    private final long bit;
    private final String displayName;
    private final String reason;

    BotPermission(long bit, String displayName, String reason) {
        this.bit = bit;
        this.displayName = displayName;
        this.reason = reason;
    }

    public long bit() {
        return bit;
    }

    public String displayName() {
        return displayName;
    }

    public String reason() {
        return reason;
    }
}
