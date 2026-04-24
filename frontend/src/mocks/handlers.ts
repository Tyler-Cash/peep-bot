import { http, HttpResponse } from "msw";
import type {
  Category,
  EventDetailDto,
  EventDto,
  RsvpStatus,
} from "@/lib/types";
import {
  currentUser,
  findEvent,
  guild,
  rewindStats,
  setRsvp,
  store,
} from "./fixtures";

const API = (path: string) => new RegExp(`(^|/api)${path}$`);

function stripDetail(e: EventDetailDto): EventDto {
  const { accepted, maybe, declined, ...rest } = e;
  void accepted;
  void maybe;
  void declined;
  return rest;
}

export const handlers = [
  http.get(API("/csrf"), () =>
    HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "mock-csrf-token" }),
  ),

  http.get(API("/auth/is-logged-in"), () => HttpResponse.json(currentUser)),

  http.get(API("/guild"), () => HttpResponse.json([guild])),
  http.get(API("/guild/[^/]+"), ({ params }) => {
    const id = (params as Record<string, string>)["0"] ?? guild.id;
    if (id !== guild.id) return new HttpResponse(null, { status: 404 });
    return HttpResponse.json(guild);
  }),

  http.get(API("/event"), () => {
    const now = Date.now();
    const list = store.events
      .filter(
        (e) => new Date(e.dateTime).getTime() >= now - 1000 * 60 * 60 * 24,
      )
      .sort((a, b) => +new Date(a.dateTime) - +new Date(b.dateTime))
      .map(stripDetail);
    return HttpResponse.json({ content: list, totalElements: list.length });
  }),

  http.get(API("/event/[0-9]+"), ({ request }) => {
    const id = Number(new URL(request.url).pathname.split("/").pop());
    const ev = findEvent(id);
    return ev ? HttpResponse.json(ev) : new HttpResponse(null, { status: 404 });
  }),

  http.put(API("/event"), async ({ request }) => {
    const body = (await request.json()) as Partial<EventDto>;
    const id = store.nextId++;
    const now = new Date().toISOString();
    const created: EventDetailDto = {
      id,
      name: body.name ?? "untitled",
      description: body.description ?? "",
      location: body.location ?? "",
      capacity: body.capacity ?? 0,
      cost: body.cost ?? 0,
      dateTime: body.dateTime ?? now,
      host: currentUser.username,
      hostAvatarUrl: currentUser.avatarUrl,
      category: (body.category as Category) ?? "outdoor",
      state: "ACTIVE",
      accepted: [
        {
          snowflake: currentUser.discordId,
          name: currentUser.username,
          instant: now,
          avatarUrl: currentUser.avatarUrl,
          hue: "#7BC24F",
        },
      ],
      maybe: [],
      declined: [],
    };
    store.events.push(created);
    return HttpResponse.json(created);
  }),

  http.patch(API("/event"), async ({ request }) => {
    const body = (await request.json()) as { id: number } & Partial<EventDto>;
    const ev = findEvent(body.id);
    if (!ev) return new HttpResponse(null, { status: 404 });
    Object.assign(ev, body);
    return HttpResponse.json(ev);
  }),

  http.delete(API("/event"), ({ request }) => {
    const id = Number(new URL(request.url).searchParams.get("id"));
    const i = store.events.findIndex((e) => e.id === id);
    if (i >= 0) store.events.splice(i, 1);
    return new HttpResponse(null, { status: 204 });
  }),

  http.post(API("/event/[0-9]+/rsvp"), async ({ request }) => {
    const id = Number(
      new URL(request.url).pathname.split("/").slice(-2, -1)[0],
    );
    const body = (await request.json()) as { status: RsvpStatus | "none" };
    const ev = setRsvp(id, currentUser, body.status);
    return ev ? HttpResponse.json(ev) : new HttpResponse(null, { status: 404 });
  }),

  http.post(API("/event/[0-9]+/cancel"), ({ request }) => {
    const id = Number(
      new URL(request.url).pathname.split("/").slice(-2, -1)[0],
    );
    const ev = findEvent(id);
    if (!ev) return new HttpResponse(null, { status: 404 });
    ev.state = "CANCELLED";
    return HttpResponse.json(ev);
  }),

  http.get(API("/rewind"), ({ request }) => {
    const year = Number(
      new URL(request.url).searchParams.get("year") ?? new Date().getFullYear(),
    );
    return HttpResponse.json(rewindStats(year));
  }),
  http.get(API("/rewind/me"), ({ request }) => {
    const year = Number(
      new URL(request.url).searchParams.get("year") ?? new Date().getFullYear(),
    );
    return HttpResponse.json(rewindStats(year));
  }),
  http.get(API("/rewind/years"), () => HttpResponse.json([2024, 2025, 2026])),
];
