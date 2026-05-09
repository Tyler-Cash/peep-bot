// Canonical lifecycle stages for the admin Events screen.
//
// These mirror the backend's EventState + EventLifecycleEvent records but in a UI-friendly shape:
// each stage has a column position in the pipeline view, an emoji, a human label, and the
// lifecycle-event-type that needs to be re-published to retrigger the listener producing it.
//
// Keep this in lockstep with backend/dev.tylercash.event.lifecycle.EventLifecycleEvent. The set of
// records there is small and rarely changes; if it grows, mirror the addition here.

export type StageKind = "transition" | "side";

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
  },
  {
    id: "init-channel",
    label: "init channel",
    emoji: "🔒",
    kind: "transition",
    trigger: "EventCreated",
    state: "INIT_CHANNEL",
    listener: "DiscordChannelInitListener",
  },
  {
    id: "init-roles",
    label: "init roles",
    emoji: "👥",
    kind: "transition",
    trigger: "EventChannelReady",
    state: "INIT_ROLES",
    listener: "DiscordRolesInitListener",
  },
  {
    id: "classify",
    label: "classify",
    emoji: "🏷️",
    kind: "transition",
    trigger: "EventRolesReady",
    state: "CLASSIFY",
    listener: "EventClassifyListener",
  },
  {
    id: "planned",
    label: "planned",
    emoji: "📅",
    kind: "transition",
    trigger: "EventClassified",
    state: "PLANNED",
    listener: "EventInitCompleteListener",
  },
  {
    id: "album-prep",
    label: "album prep",
    emoji: "🖼️",
    kind: "side",
    trigger: "EventPreNotified",
    state: null,
    listener: "ImmichAlbumPrepListener",
  },
  {
    id: "pre-notified",
    label: "pre-notified",
    emoji: "🔔",
    kind: "transition",
    trigger: "EventPreNotifyDue",
    state: "PRE_NOTIFIED",
    listener: "PreEventNotificationListener",
  },
  {
    id: "completed",
    label: "completed",
    emoji: "✅",
    kind: "transition",
    trigger: "EventCompletionDue",
    state: "POST_COMPLETED",
    listener: "EventCompleteListener",
  },
  {
    id: "album-post",
    label: "album post",
    emoji: "📸",
    kind: "side",
    trigger: "EventCompleted",
    state: null,
    listener: "ImmichAlbumPostListener",
  },
  {
    id: "archived",
    label: "archived",
    emoji: "📦",
    kind: "transition",
    trigger: "EventArchivalDue",
    state: "ARCHIVED",
    listener: "EventArchiveListener",
  },
  {
    id: "cancelled",
    label: "cancelled",
    emoji: "✕",
    kind: "transition",
    trigger: "EventCancelRequested",
    state: "CANCELLED",
    listener: "EventCancelListener",
  },
  {
    id: "deleted",
    label: "deleted",
    emoji: "🗑",
    kind: "transition",
    trigger: "EventDeleteRequested",
    state: "DELETED",
    listener: "EventDeleteListener",
  },
];

export const STAGE_BY_ID: Record<string, LifecycleStage> = Object.fromEntries(
  LIFECYCLE_STAGES.map((s) => [s.id, s]),
);

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
