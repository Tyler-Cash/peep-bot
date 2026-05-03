# Restricted permissions install

The default "Add to Discord" link grants Peep Bot every permission it might
ever need across the entire server. That's the easy path. If you'd rather
keep the bot scoped to a single category (for example, `#outings`) and grant
nothing server-wide unless absolutely required, follow this guide.

## What stays server-wide

One permission cannot be scoped to a category, because the underlying Discord
operation is server-level:

| Permission | Why it must be global |
|---|---|
| Manage Roles | The bot creates per-event `Accepted` / `Declined` / `Maybe` roles and adds/removes them when members RSVP. Role creation is a server-level action; Discord has no per-channel override for it. |

If you cannot grant Manage Roles, the RSVP buttons stop working — there is no
graceful degradation. Don't install Peep Bot under those conditions.

## What can be scoped to a category

Everything else Peep Bot uses can be granted as a category-level permission
override on the events category alone. Channels created by the bot under that
category inherit the override, so new event channels work without further
setup.

| Permission | What breaks if missing |
|---|---|
| View Channels | Bot can't see the category at all — nothing works. |
| Manage Channels | Can't create, sort, archive, or delete event channels. |
| Send Messages | Can't post the event embed, RSVP notifications, or the album link. |
| Embed Links | Event embed renders as plain text. |
| Pin Messages | Event embed, privacy notice, and album link aren't pinned. |
| Read Message History | Discord's auto-generated "pinned a message" notification stays in private event channels. Cosmetic only. |
| Use External Emojis | RSVP buttons fall back to the default unicode emojis if you've configured a custom emoji from another server. No effect if you use the unicode defaults. |
| Mention @everyone | Bot can't ping the `@events` role on event creation, unless you mark that role mentionable. (Discord requires this permission to ping any non-mentionable role, despite the misleading name.) |

## Setup steps

1. **Install with the bot scope only.** Open the install URL with permissions
   stripped:

   ```
   https://discord.com/api/oauth2/authorize?client_id=<CLIENT_ID>&scope=bot&permissions=0
   ```

   Pick the target server and click Authorize. Discord will create a
   `Peep Bot` role in the server with no permissions.

2. **Grant Manage Roles to the `Peep Bot` role at the server level.**
   Server Settings → Roles → Peep Bot → enable **Manage Roles**. Leave every
   other server-wide toggle off.

3. **Position the `Peep Bot` role correctly.** It must be above the per-event
   roles it will create (it will be, automatically) and below any role you
   don't want it touching (admin roles, moderator roles).

4. **Grant per-category overrides on your events category** (e.g. `#outings`).
   Right-click the category → Edit Category → Permissions → add the
   `Peep Bot` role and enable, at minimum:

   - View Channels
   - Manage Channels
   - Send Messages
   - Embed Links
   - Pin Messages
   - Read Message History *(skip if you don't mind the system pin notice)*
   - Use External Emojis *(skip if you'll use unicode RSVP emojis)*
   - Mention @everyone *(skip if you mark the `events` role mentionable yourself)*

5. **Verify.** Create a test event in the category and RSVP to it. If the
   embed posts, the channel is created, and clicking RSVP toggles a role,
   you're done.

## What this does **not** restrict

- The bot still receives `MessageReceivedEvent` for every channel it can see.
  In this configuration that's only the events category, which is a feature,
  not a bug.
- The OAuth login flow on the web app is unaffected — login uses the
  `identify` scope and doesn't require any bot permissions.
- Per-guild feature flags (Immich, Rewind, Google autocomplete) are still
  controlled separately via the bot-admin panel, not by this permission set.
