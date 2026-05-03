# Installing Peep Bot with limited permissions

The "Add to Discord" button in the app gives Peep Bot every permission it
might use, across your whole server. That's the simple option — pick it
unless you have a reason not to.

If you'd rather keep Peep Bot confined to one category (say, `#outings`)
and out of every other channel in your server, this guide walks you
through it.

## Manage Roles

Peep Bot uses Manage Roles to create an `Accepted`, `Declined`, and
`Maybe` role for each event, and to add and remove those roles when
members click an RSVP button. It doesn't touch any role it didn't
create.

You can skip it. RSVPs still work — buttons still record who clicked
and show the attendee list — you just don't get the per-event roles,
so you can't `@Accepted` to ping everyone coming to an event.

Discord doesn't let you scope this permission to a single category;
it's granted at the server level or not at all.

## Everything else: scope it to one category

The remaining permissions can all be set as overrides on a single
category. Channels Peep Bot creates inside that category inherit the
overrides, so new event channels work without any extra setup.

| Permission | What you lose if you skip it |
|---|---|
| View Channels | Required. Without it the bot can't see the category and nothing works. |
| Manage Channels | Required. The bot creates and archives event channels. |
| Send Messages | Required. The bot can't post the event itself or RSVP updates. |
| Embed Links | The event details show up as plain text instead of a nicely formatted card. |
| Pin Messages | The event card and album link aren't pinned to the top of the channel. Everything still works, you just have to scroll for them. |
| Read Message History | Discord's "Peep Bot pinned a message" system notice stays in private event channels. Purely cosmetic. |
| Use External Emojis | If you've configured custom RSVP emojis from another server, the buttons fall back to standard ones. No effect if you use the defaults. |
| Mention Everyone | The bot can't ping your `@events` role when an event is announced. Workaround: in **Server Settings → Roles → events**, turn on "Allow anyone to @mention this role" — pings then work without this permission. |

## Setup

1. **Onboard the bot with no permissions.** Click "Add to Discord" as
   usual, but on Discord's authorization screen deselect every
   permission checkbox before clicking **Authorize**. Discord adds a
   `Peep Bot` role to your server with no permissions granted.

2. **Server-wide: grant Manage Roles** *(optional — skip if you don't
   want per-event roles)*. Server Settings → Roles → Peep Bot → turn
   on **Manage Roles**. Leave every other server-wide toggle off.

3. **Check role order.** The `Peep Bot` role needs to sit *below* any
   role you don't want it touching (admin/moderator) and *above* the
   per-event roles it will create. Discord places new bot roles low by
   default, which is correct — just don't drag it above your admin
   role.

4. **Category-wide: grant the rest.** Right-click your events category
   → **Edit Category → Permissions** → add **Peep Bot** and enable:

   - View Channels
   - Manage Channels
   - Send Messages
   - Embed Links
   - Pin Messages *(optional — skip if you don't care about pinning)*
   - Read Message History *(optional — skip if the system pin notice
     doesn't bother you)*
   - Use External Emojis *(optional — skip if you use default RSVP
     emojis)*
   - Mention Everyone *(optional — skip if you marked `@events`
     mentionable in step 2-ish)*

5. **Test it.** Create an event in the category, click an RSVP button.
   If the event card shows up, the channel gets created, and the
   button updates the attendee list, you're done.

## What this doesn't change

- **Login still works the same.** Signing into the web app uses your
  Discord account info and doesn't depend on any of these bot
  permissions.
