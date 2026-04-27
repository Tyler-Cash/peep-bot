"use client";

import useSWR, { mutate as globalMutate } from "swr";
import { apiFetch, api } from "./api";
import type {
  EventDetailDto,
  EventDto,
  Guild,
  GuildSettingsDto,
  RewindStats,
  RsvpStatus,
  UserInfo,
} from "./types";

const fetcher = <T>(path: string) => apiFetch<T>(path);

const ACTIVE_GUILD_KEY = "peepbot.activeGuild";

export function useCurrentUser() {
  return useSWR<UserInfo>("/auth/is-logged-in", fetcher);
}

export function useGuilds() {
  return useSWR<Guild[]>("/guild", fetcher);
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
  }
}

export function useActiveGuild(): Guild | null {
  const { data } = useGuilds();
  const id = getActiveGuildId(data);
  return data?.find((g) => g.id === id) ?? data?.[0] ?? null;
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

export function useRewind(year: number, scope: "guild" | "me" = "guild") {
  const guild = useActiveGuild();
  const key = guild ? (["rewind", guild.id, scope, year] as const) : null;
  return useSWR<RewindStats>(key, () =>
    fetcher<RewindStats>(
      `/rewind${scope === "me" ? "/me" : ""}?guildId=${guild!.id}&year=${year}`,
    ),
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

export async function logout() {
  const base = api.base;
  await fetch(`${base}/auth/logout`, {
    method: "POST",
    credentials: "include",
  });
  await globalMutate(() => true, undefined);
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

export function useGuildSettings(guildId: string | null) {
  return useSWR<GuildSettingsDto>(
    guildId ? `/guild/${guildId}/settings` : null,
    fetcher,
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
