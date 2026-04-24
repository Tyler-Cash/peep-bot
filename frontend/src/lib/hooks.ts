"use client";

import useSWR, { mutate as globalMutate } from "swr";
import { apiFetch } from "./api";
import type {
  EventDetailDto,
  EventDto,
  Guild,
  RewindStats,
  RsvpStatus,
  UserInfo,
} from "./types";

const fetcher = <T,>(path: string) => apiFetch<T>(path);

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
  return useSWR<EventsPage>(key, () => fetcher<EventsPage>("/event"));
}

export function useEvent(id: number | string) {
  const guild = useActiveGuild();
  const key = guild && id ? (["event", guild.id, String(id)] as const) : null;
  return useSWR<EventDetailDto>(key, () => fetcher<EventDetailDto>(`/event/${id}`));
}

export function useRewind(year: number, scope: "guild" | "me" = "guild") {
  const guild = useActiveGuild();
  const key = guild ? (["rewind", guild.id, scope, year] as const) : null;
  return useSWR<RewindStats>(key, () =>
    fetcher<RewindStats>(`/rewind${scope === "me" ? "/me" : ""}?year=${year}`),
  );
}

export function invalidateEvents(guildId: string) {
  return globalMutate((k) => Array.isArray(k) && k[0] === "events" && k[1] === guildId);
}

export function invalidateEvent(guildId: string, eventId: number | string) {
  return globalMutate(
    (k) => Array.isArray(k) && k[0] === "event" && k[1] === guildId && k[2] === String(eventId),
  );
}

export async function submitRsvp(
  guildId: string,
  eventId: number,
  status: RsvpStatus | "none",
) {
  await apiFetch<EventDetailDto>(`/event/${eventId}/rsvp`, {
    method: "POST",
    body: JSON.stringify({ status }),
  });
  await Promise.all([invalidateEvents(guildId), invalidateEvent(guildId, eventId)]);
}

export async function createEvent(guildId: string, body: Partial<EventDto>) {
  const created = await apiFetch<EventDetailDto>("/event", {
    method: "PUT",
    body: JSON.stringify(body),
  });
  await invalidateEvents(guildId);
  return created;
}
