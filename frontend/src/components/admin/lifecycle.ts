// Canonical lifecycle stages for the admin Events screen.
//
// These mirror the backend's EventState + EventLifecycleEvent records but in a UI-friendly shape:
// each stage has a column position in the pipeline view, an emoji, a human label, and the
// lifecycle-event-type that needs to be re-published to retrigger the listener producing it.
//
// Keep this in lockstep with backend/dev.tylercash.event.lifecycle.EventLifecycleEvent. The set of
// records there is small and rarely changes; if it grows, mirror the addition here.

export type StageKind = "transition" | "side";

/**
 * Idempotency / safety tag for a stage. Rendered as a colored chip in the replay console so the
 * admin knows what risk they're taking before clicking. Map:
 *   - safe          : pure compute or dedup'd write, fine to re-run
 *   - posts-discord : will create or update a Discord message/embed
 *   - sends-dm      : will DM users — visible to the recipient
 *   - creates-channel : creates/renames a Discord channel
 *   - destructive   : tears down channels, roles, or records
 */
export type StageIdempotency =
  | "safe"
  | "posts-discord"
  | "sends-dm"
  | "creates-channel"
  | "destructive";

export type LifecycleStage = {
  id: string;
  label: string;
  emoji: string;
  kind: StageKind;
  /**
   * The lifecycle event that, when published, drives the listener that produces this stage.
   * For example, the `init-channel` listener (DiscordChannelInitListener) consumes
   * `EventCreated`, so to "replay" the init-channel stage we publish `EventCreated`.
   */
  trigger: string;
  /** Backend EventState the listener writes after success, if any. */
  state: string | null;
  /** Listener bean name(s) that will react to the trigger. Informational only. */
  listener: string;
  /**
   * Concrete user-facing description of what re-running this stage will do. Reads at the
   * "what will happen" box in the replay console — write it so a tired admin at 2am can tell
   * whether clicking the button is safe.
   */
  humanEffect: string;
  idempotency: StageIdempotency;
  /** Optional one-liner for how the listener avoids producing duplicates. */
  dedup?: string;
};

export const LIFECYCLE_STAGES: LifecycleStage[] = [
  {
    id: "created",
    label: "created",
    emoji: "📝",
    kind: "transition",
    trigger: "EventCreated",
    state: "CREATED",
    listener: "—",
    humanEffect: "Marker stage — the event row exists. Nothing runs on replay of this stage itself.",
    idempotency: "safe",
  },
  {
    id: "init-channel",
    label: "init channel",
    emoji: "🔒",
    kind: "transition",
    trigger: "EventCreated",
    state: "INIT_CHANNEL",
    listener: "DiscordChannelInitListener",
    humanEffect:
      "Creates the per-event Discord text channel under the events category (or reuses an existing one matched by stored channel id).",
    idempotency: "creates-channel",
    dedup: "channel id stored on the event row — re-runs reuse the existing channel.",
  },
  {
    id: "init-roles",
    label: "init roles",
    emoji: "👥",
    kind: "transition",
    trigger: "EventChannelReady",
    state: "INIT_ROLES",
    listener: "DiscordRolesInitListener",
    humanEffect:
      "Grants the per-event Discord role to the creator + RSVP-going users so they can see the channel.",
    idempotency: "safe",
    dedup: "role grants are idempotent — re-applying a role is a no-op.",
  },
  {
    id: "classify",
    label: "classify",
    emoji: "🏷️",
    kind: "transition",
    trigger: "EventRolesReady",
    state: "CLASSIFY",
    listener: "EventClassifyListener",
    humanEffect:
      "Classifies the event (category/tags) using the configured classifier. Pure compute, writes a category back to the row.",
    idempotency: "safe",
  },
  {
    id: "planned",
    label: "planned",
    emoji: "📅",
    kind: "transition",
    trigger: "EventClassified",
    state: "PLANNED",
    listener: "EventInitCompleteListener",
    humanEffect:
      "Finalises init — posts the initial event embed to the event's channel and transitions to PLANNED.",
    idempotency: "posts-discord",
    dedup: "edits the existing embed when a message id is already stored; otherwise posts a new one.",
  },
  {
    id: "tfnsw-notice",
    label: "tfnsw notice",
    emoji: "🚆",
    kind: "side",
    trigger: "EventCreated",
    state: null,
    listener: "TfnswEventCreatedListener",
    humanEffect:
      "Resolves the event location via Google Places, fetches GTFS-R alerts + Live Traffic NSW, and posts a transport notice embed to the event channel if anything is noteworthy.",
    idempotency: "posts-discord",
    dedup: "skips when the snapshot hash matches the last post (tfnsw_event_snapshot). Guild-gated by tfnsw_enabled.",
  },
  {
    id: "album-prep",
    label: "album prep",
    emoji: "🖼️",
    kind: "side",
    trigger: "EventPreNotified",
    state: null,
    listener: "ImmichAlbumPrepListener",
    humanEffect:
      "Creates the Immich album for the event so attendees can drop photos before the event runs.",
    idempotency: "safe",
    dedup: "album id stored on the event row — re-runs reuse the existing album.",
  },
  {
    id: "pre-notified",
    label: "pre-notified",
    emoji: "🔔",
    kind: "transition",
    trigger: "EventPreNotifyDue",
    state: "PRE_NOTIFIED",
    listener: "PreEventNotificationListener",
    humanEffect:
      "Posts a pre-event reminder to the event channel and pings the event role. Visible to every member of the channel.",
    idempotency: "posts-discord",
  },
  {
    id: "completed",
    label: "completed",
    emoji: "✅",
    kind: "transition",
    trigger: "EventCompletionDue",
    state: "POST_COMPLETED",
    listener: "EventCompleteListener",
    humanEffect:
      "Closes out the event — posts a wrap-up embed and transitions to POST_COMPLETED.",
    idempotency: "posts-discord",
  },
  {
    id: "album-post",
    label: "album post",
    emoji: "📸",
    kind: "side",
    trigger: "EventCompleted",
    state: null,
    listener: "ImmichAlbumPostListener",
    humanEffect:
      "Posts the Immich album link to the event channel after completion — visible to every member.",
    idempotency: "posts-discord",
    dedup: "skips when an album-post message id is already recorded.",
  },
  {
    id: "archived",
    label: "archived",
    emoji: "📦",
    kind: "transition",
    trigger: "EventArchivalDue",
    state: "ARCHIVED",
    listener: "EventArchiveListener",
    humanEffect:
      "Archives the event — deletes/locks the Discord channel and transitions to ARCHIVED.",
    idempotency: "destructive",
  },
  {
    id: "cancelled",
    label: "cancelled",
    emoji: "✕",
    kind: "transition",
    trigger: "EventCancelRequested",
    state: "CANCELLED",
    listener: "EventCancelListener",
    humanEffect:
      "Cancels the event — posts a cancellation notice and stops further scheduled work for this event.",
    idempotency: "posts-discord",
  },
  {
    id: "deleted",
    label: "deleted",
    emoji: "🗑",
    kind: "transition",
    trigger: "EventDeleteRequested",
    state: "DELETED",
    listener: "EventDeleteListener",
    humanEffect:
      "Hard-deletes the event row and tears down channel + roles. Cannot be undone.",
    idempotency: "destructive",
  },
];

export const STAGE_BY_ID: Record<string, LifecycleStage> = Object.fromEntries(
  LIFECYCLE_STAGES.map((s) => [s.id, s]),
);

/**
 * Stages whose listeners also subscribe to the same lifecycle trigger — replaying any one of
 * these republishes the trigger, which fans out to every listener. Used in the replay console
 * so the admin can see "you're about to fire N listeners" before clicking.
 */
export function coListenersForTrigger(trigger: string, exceptId?: string): LifecycleStage[] {
  return LIFECYCLE_STAGES.filter(
    (s) => s.trigger === trigger && s.id !== exceptId && s.listener !== "—",
  );
}

export const IDEMPOTENCY_LABEL: Record<StageIdempotency, string> = {
  safe: "safe to re-run",
  "posts-discord": "posts to discord",
  "sends-dm": "sends DMs",
  "creates-channel": "creates channels",
  destructive: "destructive",
};

/** Tailwind classes for the idempotency chip — paired with the label above. */
export const IDEMPOTENCY_CHIP: Record<StageIdempotency, string> = {
  safe: "bg-leafLt text-[#1F4410] border-[#1F4410]",
  "posts-discord": "bg-[#FFF0A6] text-ink border-ink",
  "sends-dm": "bg-[#FFD9B0] text-[#7A3A00] border-[#7A3A00]",
  "creates-channel": "bg-[#E8DAFF] text-[#3D1F7A] border-[#3D1F7A]",
  destructive: "bg-[#FFE5E5] text-[#7A1A1A] border-[#7A1A1A]",
};

// State columns in the pipeline view. Excludes side-effect stages (which don't change state) and
// the cancelled / deleted terminal columns (rendered demoted on a separate strip).
export const PIPELINE_LIVE_STATE_IDS = [
  "created",
  "init-channel",
  "init-roles",
  "classify",
  "planned",
  "pre-notified",
];
export const PIPELINE_TERMINAL_STATE_IDS = [
  "completed",
  "archived",
  "cancelled",
  "deleted",
];

// Backend EventState string → UI stage id.
export function stageIdForState(state: string | undefined | null): string {
  if (!state) return "created";
  switch (state.toUpperCase()) {
    case "CREATED":
      return "created";
    case "INIT_CHANNEL":
      return "init-channel";
    case "INIT_ROLES":
      return "init-roles";
    case "CLASSIFY":
      return "classify";
    case "PLANNED":
      return "planned";
    case "PRE_NOTIFIED":
      return "pre-notified";
    case "POST_COMPLETED":
      return "completed";
    case "ARCHIVED":
      return "archived";
    case "CANCELLED":
      return "cancelled";
    case "DELETED":
      return "deleted";
    default:
      return "created";
  }
}

// Lightweight category palette mirroring tailwind.config.ts cat-* tokens — used by event tiles.
// Keeping the source of truth in JS/TS rather than purging classes lets us safelist arbitrary
// category strings from the backend without bloating the Tailwind output.
export const CAT_PALETTE: Record<string, { bg: string; ink: string; emoji: string }> = {
  movie: { bg: "#FFD89B", ink: "#5A2E08", emoji: "🎬" },
  trivia: { bg: "#FFF0A6", ink: "#3D3A14", emoji: "🧠" },
  comedy: { bg: "#FFB8D9", ink: "#5A1F3D", emoji: "🎤" },
  food: { bg: "#B8E89A", ink: "#1F4410", emoji: "🍜" },
  outdoor: { bg: "#A5D8E0", ink: "#0E3B44", emoji: "🥾" },
  game: { bg: "#D4B8FF", ink: "#2E1A5A", emoji: "🎲" },
  music: { bg: "#FFC9B8", ink: "#4A1F0E", emoji: "🎶" },
  unknown: { bg: "#EEE8DA", ink: "#1A1D18", emoji: "•" },
};

export function catFor(category: string | null | undefined) {
  if (!category) return CAT_PALETTE.unknown;
  return CAT_PALETTE[category.toLowerCase()] ?? CAT_PALETTE.unknown;
}
