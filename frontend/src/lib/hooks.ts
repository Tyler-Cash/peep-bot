"use client";

import { useSyncExternalStore } from "react";
import useSWR, { mutate as globalMutate } from "swr";
import { z } from "zod";
import { apiFetch, api } from "./api";
import { clearSwrCache } from "./swrCache";
import { zGuildDto, zGuildSettingsDto, zUserInfoDto } from "./api/generated/zod.gen";
import type {
  EventDetailDto,
  EventDto,
  GalleryAlbumDto,
  Guild,
  GuildSettingsDto,
  RewindStats,
  RsvpStatus,
  UserInfo,
} from "./types";

const fetcher = <T>(path: string) => apiFetch<T>(path);
const guildListSchema = z.array(zGuildDto);

const ACTIVE_GUILD_KEY = "peepbot.activeGuild";
const ACTIVE_GUILD_EVENT = "peepbot:active-guild-changed";

function subscribeActiveGuild(cb: () => void) {
  if (typeof window === "undefined") return () => {};
  window.addEventListener(ACTIVE_GUILD_EVENT, cb);
  window.addEventListener("storage", cb);
  return () => {
    window.removeEventListener(ACTIVE_GUILD_EVENT, cb);
    window.removeEventListener("storage", cb);
  };
}

function getStoredActiveGuildId(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(ACTIVE_GUILD_KEY);
}

export function useCurrentUser() {
  return useSWR<UserInfo>("/auth/is-logged-in", (path) =>
    apiFetch(path, {}, zUserInfoDto),
  );
}

export function useGuilds() {
  return useSWR<Guild[]>("/guild", (path) => apiFetch(path, {}, guildListSchema));
}

export function getActiveGuildId(guilds: Guild[] | undefined): string | null {
  if (typeof window === "undefined") return guilds?.[0]?.id ?? null;
  const stored = window.localStorage.getItem(ACTIVE_GUILD_KEY);
  if (stored && guilds?.some((g) => g.id === stored)) return stored;
  return guilds?.[0]?.id ?? null;
}

export function setActiveGuildId(id: string) {
  if (typeof window !== "undefined") {
    window.localStorage.setItem(ACTIVE_GUILD_KEY, id);
    window.dispatchEvent(new Event(ACTIVE_GUILD_EVENT));
  }
}

export function useActiveGuild(): Guild | null {
  const { data } = useGuilds();
  const stored = useSyncExternalStore(
    subscribeActiveGuild,
    getStoredActiveGuildId,
    () => null,
  );
  if (!data || data.length === 0) return null;
  if (stored) {
    const match = data.find((g) => g.id === stored);
    if (match) return match;
  }
  return data[0];
}

/**
 * Like useActiveGuild but resolves the stored active id against the admin-guilds
 * superset (every guild peepbot is in). Used by the admin panel + the GuildSwitcher
 * when on /admin* so service admins can scope to guilds they aren't a member of.
 * Falls back to the first admin guild when the stored id is unknown.
 */
export function useActiveAdminGuild(): AdminGuild | null {
  const { data } = useAdminGuilds();
  const stored = useSyncExternalStore(
    subscribeActiveGuild,
    getStoredActiveGuildId,
    () => null,
  );
  if (!data || data.length === 0) return null;
  if (stored) {
    const match = data.find((g) => g.guildId === stored);
    if (match) return match;
  }
  return data[0];
}

type EventsPage = { content: EventDto[]; totalElements: number };

export function useEvents() {
  const guild = useActiveGuild();
  const key = guild ? (["events", guild.id] as const) : null;
  return useSWR<EventsPage>(key, () =>
    fetcher<EventsPage>(`/event?guildId=${guild!.id}`),
  );
}

export function useEvent(id: number | string) {
  const guild = useActiveGuild();
  const key = guild && id ? (["event", guild.id, String(id)] as const) : null;
  return useSWR<EventDetailDto>(key, () =>
    fetcher<EventDetailDto>(`/event/${id}`),
  );
}

/**
 * Recent/popular venues for the active guild, derived from the events we've
 * already loaded. Returns distinct location strings sorted by frequency.
 * No extra network — shares the SWR cache with useEvents(), which is itself
 * keyed by active guild id, so switching guilds automatically re-derives
 * this list from that guild's events. When we outgrow the feed's history,
 * swap this to a dedicated per-guild endpoint without touching callers.
 */
export function useRecentLocations(limit = 8): string[] {
  const { data } = useEvents();
  const events = data?.content ?? [];
  const counts = new Map<string, number>();
  for (const e of events) {
    if (!e.location) continue;
    counts.set(e.location, (counts.get(e.location) ?? 0) + 1);
  }
  return Array.from(counts.entries())
    .sort((a, b) => b[1] - a[1])
    .slice(0, limit)
    .map(([loc]) => loc);
}

export function useRewind(year: number | null) {
  const guild = useActiveGuild();
  const key = guild ? (["rewind", guild.id, year] as const) : null;
  return useSWR<RewindStats>(key, () => {
    const params = new URLSearchParams({ guildId: guild!.id });
    if (year !== null) params.set("year", String(year));
    return fetcher<RewindStats>(`/rewind?${params.toString()}`);
  });
}

export function useGallery() {
  const guild = useActiveGuild();
  const key = guild ? (["gallery", guild.id] as const) : null;
  return useSWR<GalleryAlbumDto[]>(key, () =>
    fetcher<GalleryAlbumDto[]>(`/gallery?guildId=${guild!.id}`),
  );
}

export function invalidateEvents(guildId: string) {
  return globalMutate(
    (k) => Array.isArray(k) && k[0] === "events" && k[1] === guildId,
  );
}

export function invalidateEvent(guildId: string, eventId: number | string) {
  return globalMutate(
    (k) =>
      Array.isArray(k) &&
      k[0] === "event" &&
      k[1] === guildId &&
      k[2] === String(eventId),
  );
}

export async function submitRsvp(
  guildId: string,
  eventId: number | string,
  status: RsvpStatus | "none",
) {
  await apiFetch<EventDetailDto>(`/event/${eventId}/rsvp`, {
    method: "POST",
    body: JSON.stringify({ status }),
  });
  await Promise.all([
    invalidateEvents(guildId),
    invalidateEvent(guildId, eventId),
  ]);
}

export async function createEvent(guildId: string, body: Partial<EventDto>) {
  const result = await apiFetch<{ id: string | number }>("/event", {
    method: "PUT",
    body: JSON.stringify({ ...body, guildId }),
  });
  await invalidateEvents(guildId);
  return result;
}

export async function cancelEvent(guildId: string, eventId: number | string) {
  await apiFetch(`/event/${eventId}/cancel`, { method: "POST" });
  await Promise.all([
    invalidateEvents(guildId),
    invalidateEvent(guildId, eventId),
  ]);
}

export async function createPrivateChannel(
  guildId: string,
  eventId: number | string,
) {
  await apiFetch(`/event/${eventId}/private-channel`, { method: "POST" });
  await invalidateEvent(guildId, eventId);
}

export async function recategorizeEvent(guildId: string, eventId: number | string) {
  await apiFetch(`/event/${eventId}/recategorize`, { method: "POST" });
  await invalidateEvent(guildId, eventId);
}

export async function logout() {
  try {
    await apiFetch("/auth/logout", { method: "POST" });
  } catch {
    // Logout is best-effort: ignore network/CSRF/401 errors and proceed
    // to clear local state and redirect.
  }
  clearSwrCache();
  api.invalidateCsrf();
  window.location.href = "/login";
}

export async function removeAttendee(
  guildId: string,
  eventId: number | string,
  snowflake: string | null,
  name: string,
) {
  const params = new URLSearchParams();
  if (snowflake) params.set("snowflake", snowflake);
  else params.set("name", name);
  await apiFetch<{ message: string }>(`/event/${eventId}/attendee?${params}`, {
    method: "DELETE",
  });
  await Promise.all([
    invalidateEvents(guildId),
    invalidateEvent(guildId, eventId),
  ]);
}

export async function updateEvent(
  guildId: string,
  eventId: number | string,
  body: {
    name?: string;
    description?: string;
    location?: string;
    locationPlaceId?: string;
    capacity?: number;
    dateTime?: string;
  },
) {
  // Backend EventUpdateDto: id (UUID), name, description, location, capacity, dateTime, accepted (Set<String>)
  // `accepted` must be present (even if empty) to avoid a NullPointerException in the controller.
  await apiFetch<{ message: string }>("/event", {
    method: "PATCH",
    body: JSON.stringify({ id: eventId, accepted: [], ...body }),
  });
  await Promise.all([
    invalidateEvents(guildId),
    // Optimistically merge updated fields into the cached event so the detail
    // page shows new values immediately rather than waiting for the refetch.
    globalMutate(
      (k) =>
        Array.isArray(k) &&
        k[0] === "event" &&
        k[1] === guildId &&
        k[2] === String(eventId),
      (current: EventDetailDto | undefined) =>
        current ? { ...current, ...body } : undefined,
      { revalidate: true },
    ),
  ]);
}

export type InstallPermission = { name: string; reason: string };

export function useInstallUrl() {
  return useSWR<{ url: string; permissions: InstallPermission[] }>("/install-url", fetcher);
}

export type GuildFeatures = {
  immichEnabled: boolean;
  googleAutocompleteEnabled: boolean;
  rewindEnabled: boolean;
  contractsEnabled: boolean;
};

export function useGuildFeatures(guildId: string | null | undefined) {
  return useSWR<GuildFeatures>(
    guildId ? `/guild/${guildId}/features` : null,
    fetcher,
  );
}

export type AdminGuild = {
  guildId: string;
  name: string | null;
  active: boolean;
  immichEnabled: boolean;
  googleAutocompleteEnabled: boolean;
  rewindEnabled: boolean;
  contractsEnabled: boolean;
  tfnswEnabled: boolean;
  // Extended fields populated by the admin panel — nullable so older mocks/tests still type-check.
  memberCount?: number | null;
  channelName?: string | null;
  locationName?: string | null;
  upcomingEventCount?: number;
  totalEventCount?: number;
  failingInvocations?: number;
};

export function useAdminGuilds() {
  // Only fetch for actual admins. Otherwise the call 403s, and api.ts's
  // live-mode 401/403 → /login redirect kicks the user into a login loop
  // (login → / → GuildSwitcher mounts → /admin/guilds 403 → /login → …).
  const { data: user } = useCurrentUser();
  return useSWR<AdminGuild[]>(user?.admin ? "/admin/guilds" : null, fetcher);
}

export async function updateGuildFeatures(
  guildId: string,
  body: Partial<{
    immichEnabled: boolean;
    googleAutocompleteEnabled: boolean;
    rewindEnabled: boolean;
    contractsEnabled: boolean;
    tfnswEnabled: boolean;
  }>,
) {
  await apiFetch<AdminGuild>(`/admin/guilds/${guildId}/features`, {
    method: "PATCH",
    body: JSON.stringify(body),
  });
  await globalMutate("/admin/guilds", undefined, { revalidate: true });
}

// ---------- admin monitor & lifecycle hooks ----------------------------------

export type AdminHealthComponent = { status: string; detail: string };
export type AdminHealth = {
  components: Record<string, AdminHealthComponent>;
  uptimeSeconds: number;
  syncedAt: string;
};

export function useAdminHealth() {
  const { data: user } = useCurrentUser();
  return useSWR<AdminHealth>(user?.admin ? "/admin/health" : null, fetcher, {
    refreshInterval: 30_000,
  });
}

export type AdminJob = {
  id: string;
  label: string;
  cron: string;
  emits: string;
  lastRun: string | null;
  nextRun: string | null;
  lastDuration: string | null;
  lastStatus: string;
};

export function useAdminJobs() {
  const { data: user } = useCurrentUser();
  return useSWR<AdminJob[]>(user?.admin ? "/admin/jobs" : null, fetcher, {
    refreshInterval: 60_000,
  });
}

export type AdminActivity = {
  ts: string;
  kind: "ok" | "warn" | "fail" | string;
  text: string;
  detail: string | null;
  eventId: string | null;
  guildId: string | null;
  lifecycleEventType: string | null;
  listenerName: string | null;
  attempts: number;
};

export function useAdminActivity(guildId: string | null | undefined) {
  const { data: user } = useCurrentUser();
  const path = guildId
    ? `/admin/activity?guildId=${encodeURIComponent(guildId)}`
    : "/admin/activity";
  return useSWR<AdminActivity[]>(user?.admin ? path : null, fetcher, {
    refreshInterval: 30_000,
  });
}

export type AdminEventHistoryEntry = {
  stage: string;
  lifecycleEventType: string;
  listenerName: string;
  ts: string;
  ok: boolean;
  attempts: number;
  detail: string | null;
};

export type AdminEvent = {
  id: string;
  guildId: string;
  name: string;
  category: string;
  state: string;
  when: string | null;
  location: string | null;
  creator: string | null;
  going: number;
  maybe: number;
  declined: number;
  createdAt: string | null;
  history: AdminEventHistoryEntry[];
};

export function useAdminGuildEvents(guildId: string | null | undefined) {
  const key = guildId
    ? (["admin-events", guildId] as const)
    : null;
  return useSWR<AdminEvent[]>(key, () =>
    fetcher<AdminEvent[]>(`/admin/events?guildId=${encodeURIComponent(guildId!)}`),
  );
}

export async function replayLifecycleEvent(payload: {
  eventId: string;
  lifecycleEventType: string;
  skipSideEffects?: boolean;
}) {
  const result = await apiFetch<{ message: string; listeners: string[] }>(
    "/admin/replay",
    {
      method: "POST",
      body: JSON.stringify(payload),
    },
  );
  // Refresh admin-events lists (so the replayed event's history reflects the queued listener
  // invocations) and the activity firehose. Listener-completion is async on the backend so the
  // first refetch may not yet show SUCCESS — SWR's polling on these hooks will catch up.
  await Promise.all([
    globalMutate(
      (k) => Array.isArray(k) && k[0] === "admin-events",
      undefined,
      { revalidate: true },
    ),
    globalMutate(
      (k) => typeof k === "string" && k.startsWith("/admin/activity"),
      undefined,
      { revalidate: true },
    ),
  ]);
  return result;
}

export type EventCreationState = { enabled: boolean };

export function useEventCreationState() {
  const { data: user } = useCurrentUser();
  return useSWR<EventCreationState>(
    user?.admin ? "/admin/event-creation" : null,
    fetcher,
  );
}

export async function setEventCreationEnabled(enabled: boolean) {
  await apiFetch(
    `/admin/event-creation/${enabled ? "enable" : "disable"}`,
    { method: "POST" },
  );
  await globalMutate("/admin/event-creation", undefined, { revalidate: true });
}

export function useGuildSettings(guildId: string | null) {
  return useSWR<GuildSettingsDto>(
    guildId ? `/guild/${guildId}/settings` : null,
    (path) => apiFetch(path, {}, zGuildSettingsDto),
  );
}

export async function updateGuildSettings(
  guildId: string,
  settings: GuildSettingsDto,
) {
  await apiFetch<GuildSettingsDto>(`/guild/${guildId}/settings`, {
    method: "PATCH",
    body: JSON.stringify(settings),
  });
  await globalMutate(`/guild/${guildId}/settings`, undefined, {
    revalidate: true,
  });
  await globalMutate("/guild", undefined, { revalidate: true });
}
