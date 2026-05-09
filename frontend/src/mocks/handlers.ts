import { http, HttpResponse } from "msw";
import type {
  EventDetailDto,
  EventDto,
  RsvpStatus,
  UserInfo,
} from "@/lib/types";
import type { AdminGuild } from "@/lib/hooks";
import {
  currentUser,
  findEvent,
  galleryAlbums,
  guild,
  guildSettings,
  secondGuild,
  rewindStats,
  setRsvp,
  store,
} from "./fixtures";
import {
  adminActivityStore,
  adminEventsByGuild,
  adminGuildsStore,
  adminHealthStore,
  adminJobsStore,
  getEventCreationEnabled,
  setEventCreationEnabledMock,
} from "./adminFixtures";

// In-memory admin guild store. The first row mirrors the public-site `guild` fixture so the
// PATCH /admin/guilds/{guildId}/features handler stays consistent with what useAdminGuilds shows.
// Additional rows come from adminGuildsStore so the Guilds table is more than a single row.
const adminGuilds: AdminGuild[] = [
  {
    guildId: guild.id,
    name: guild.name,
    active: true,
    immichEnabled: true,
    googleAutocompleteEnabled: true,
    rewindEnabled: true,
    contractsEnabled: false,
    memberCount: 24,
    channelName: "#outings",
    locationName: "Melbourne, VIC",
    upcomingEventCount: 5,
    totalEventCount: 18,
    failingInvocations: 1,
  },
  ...adminGuildsStore.filter((g) => g.guildId !== guild.id),
];

const API = (path: string) => new RegExp(`(^|/api)${path}$`);

// Mock auth state — persist to localStorage so it survives page reloads
function getMockLoggedInUser(): UserInfo | null {
  if (typeof window === "undefined") return currentUser;
  const stored = window.localStorage.getItem("mock-auth-logged-out");
  return stored === "true" ? null : currentUser;
}

function setMockLoggedOut(loggedOut: boolean): void {
  if (typeof window !== "undefined") {
    if (loggedOut) {
      window.localStorage.setItem("mock-auth-logged-out", "true");
    } else {
      window.localStorage.removeItem("mock-auth-logged-out");
    }
  }
}

// Wrap a handler to require authentication (except for login-related endpoints)
type Handler = Parameters<typeof http.get>[1];
function requireAuth(handler: Handler, publicEndpoints = ["/csrf", "/auth/logout", "/auth/is-logged-in"]): Handler {
  return (info) => {
    const pathname = new URL(info.request.url).pathname;
    const isPublic = publicEndpoints.some((ep) => pathname.includes(ep));
    if (!isPublic && !getMockLoggedInUser()) {
      return new HttpResponse(null, { status: 401 });
    }
    return handler(info);
  };
}

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

  http.get(API("/auth/is-logged-in"), () => {
    const user = getMockLoggedInUser();
    if (!user) return new HttpResponse(null, { status: 401 });
    return HttpResponse.json(user);
  }),

  http.post(API("/auth/logout"), () => {
    setMockLoggedOut(true);
    return new HttpResponse(null, { status: 200 });
  }),

  http.get(API("/install-url"), () =>
    HttpResponse.json({
      url: "https://discord.com/api/oauth2/authorize?client_id=mock&permissions=2251800082598928&scope=bot",
      permissions: [
        { name: "View channels", reason: "Look up channels and categories" },
        { name: "Manage channels", reason: "Create, sort, and archive event channels" },
        { name: "Manage roles", reason: "Per-event Accepted / Declined / Maybe roles" },
        { name: "Send messages", reason: "Event message and RSVP / cancel notifications" },
        { name: "Embed links", reason: "Event embed body" },
        { name: "Pin messages", reason: "Pin event messages in their channel" },
        {
          name: "Read message history",
          reason:
            'Find and delete Discord\'s "pinned a message" system notification in private event channels',
        },
        { name: "Use external emojis", reason: "Custom guild emoji in buttons" },
        { name: "Mention everyone", reason: "Ping the @events role" },
      ],
    }),
  ),

  http.get(API("/guild"), requireAuth(() => HttpResponse.json([guild, secondGuild]))),

  http.get(
    API("/guild/[^/]+"),
    requireAuth(({ params }) => {
      const id = (params as Record<string, string>)["0"] ?? guild.id;
      if (id === guild.id) return HttpResponse.json(guild);
      if (id === secondGuild.id) return HttpResponse.json(secondGuild);
      return new HttpResponse(null, { status: 404 });
    }),
  ),

  http.get(API("/guild/[^/]+/settings"), requireAuth(() => HttpResponse.json(guildSettings))),

  http.patch(
    API("/guild/[^/]+/settings"),
    requireAuth(async ({ request }) => {
      const body = (await request.json()) as typeof guildSettings;
      Object.assign(guildSettings, body);
      guild.primaryLocationLat = body.primaryLocationLat ?? null;
      guild.primaryLocationLng = body.primaryLocationLng ?? null;
      return HttpResponse.json(guildSettings);
    }),
  ),

  http.get(API("/api/places/geocode"), ({ request }) => {
    const url = new URL(request.url);
    const placeId = url.searchParams.get("placeId");
    if (!placeId) return new HttpResponse(null, { status: 400 });
    return HttpResponse.json({ lat: -37.8136, lng: 144.9631 });
  }),

  http.get(
    API("/event"),
    requireAuth(() => {
      const now = Date.now();
      const list = store.events
        .filter((e) => new Date(e.dateTime).getTime() >= now - 1000 * 60 * 60 * 24)
        .sort((a, b) => +new Date(a.dateTime) - +new Date(b.dateTime))
        .map(stripDetail);
      return HttpResponse.json({ content: list, totalElements: list.length });
    }),
  ),

  http.get(
    API("/event/[0-9]+"),
    requireAuth(({ request }) => {
      const id = new URL(request.url).pathname.split("/").pop() ?? "";
      const ev = findEvent(id);
      return ev ? HttpResponse.json(ev) : new HttpResponse(null, { status: 404 });
    }),
  ),

  http.put(
    API("/event"),
    requireAuth(async ({ request }) => {
      const user = getMockLoggedInUser();
      if (!user) return new HttpResponse(null, { status: 401 });
      const body = (await request.json()) as Partial<EventDto>;
      const id = String(store.nextId++);
      const now = new Date().toISOString();
      const created: EventDetailDto = {
        id,
        // guildId is required in the generated type; use a sentinel for mock data.
        guildId: body.guildId ?? "0",
        name: body.name ?? "untitled",
        description: body.description ?? "",
        location: body.location ?? "",
        capacity: body.capacity ?? 0,
        cost: body.cost ?? 0,
        dateTime: body.dateTime ?? now,
        host: user.username,
        hostAvatarUrl: user.avatarUrl ?? undefined,
        category: (body.category as string) ?? "unknown",
        state: "ACTIVE",
        hasPrivateChannel: false,
        completed: false,
        // AttendeeDto (generated) doesn't have `hue`; cast to satisfy the
        // array type — hue is a UI-only field used by Avatar for colour fallback.
        accepted: [
          {
            snowflake: user.discordId,
            name: user.username ?? undefined,
            instant: now,
            avatarUrl: user.avatarUrl ?? undefined,
          } as EventDetailDto["accepted"][0] & { hue?: string },
        ],
        maybe: [],
        declined: [],
      };
      store.events.push(created);
      return HttpResponse.json({ id: created.id, message: `Created event for ${created.name}` });
    }),
  ),

  http.patch(
    API("/event"),
    requireAuth(async ({ request }) => {
      const body = (await request.json()) as { id: string } & Partial<EventDto>;
      const ev = findEvent(body.id);
      if (!ev) return new HttpResponse(null, { status: 404 });
      Object.assign(ev, body);
      return HttpResponse.json(ev);
    }),
  ),

  http.delete(
    API("/event"),
    requireAuth(({ request }) => {
      const id = new URL(request.url).searchParams.get("id") ?? "";
      const i = store.events.findIndex((e) => e.id.toString() === id);
      if (i >= 0) store.events.splice(i, 1);
      return new HttpResponse(null, { status: 204 });
    }),
  ),

  http.post(
    API("/event/[0-9]+/rsvp"),
    requireAuth(async ({ request }) => {
      const user = getMockLoggedInUser();
      if (!user) return new HttpResponse(null, { status: 401 });
      const id = new URL(request.url).pathname.split("/").slice(-2, -1)[0];
      const body = (await request.json()) as { status: RsvpStatus | "none" };
      const ev = setRsvp(id, user, body.status);
      return ev ? HttpResponse.json(ev) : new HttpResponse(null, { status: 404 });
    }),
  ),

  http.post(
    API("/event/[0-9]+/cancel"),
    requireAuth(({ request }) => {
      const id = new URL(request.url).pathname.split("/").slice(-2, -1)[0];
      const ev = findEvent(id);
      if (!ev) return new HttpResponse(null, { status: 404 });
      ev.state = "CANCELLED";
      ev.displayState = "cancelled";
      return HttpResponse.json({ message: "Event cancelled" });
    }),
  ),

  http.post(
    API("/event/[0-9]+/private-channel"),
    requireAuth(({ request }) => {
      const id = Number(new URL(request.url).pathname.split("/").slice(-2, -1)[0]);
      const ev = findEvent(id.toString());
      if (!ev) return new HttpResponse(null, { status: 404 });
      ev.hasPrivateChannel = true;
      return HttpResponse.json({ message: "Private channel created" });
    }),
  ),

  http.get(
    API("/rewind"),
    requireAuth(({ request }) => {
      const year = Number(
        new URL(request.url).searchParams.get("year") ?? new Date().getFullYear(),
      );
      return HttpResponse.json(rewindStats(year));
    }),
  ),

  http.get(
    API("/rewind/me"),
    requireAuth(({ request }) => {
      const year = Number(
        new URL(request.url).searchParams.get("year") ?? new Date().getFullYear(),
      );
      return HttpResponse.json(rewindStats(year));
    }),
  ),

  http.get(API("/rewind/years"), requireAuth(() => HttpResponse.json([2024, 2025, 2026]))),

  http.get(
    API("/gallery"),
    requireAuth(() => {
      const sorted = [...galleryAlbums].sort(
        (a, b) => +new Date(b.eventDateTime) - +new Date(a.eventDateTime),
      );
      return HttpResponse.json(sorted);
    }),
  ),

  // Feature flags — all enabled in mock mode so existing render tests don't break
  http.get(
    API("/guild/[^/]+/features"),
    requireAuth(() =>
      HttpResponse.json({
        immichEnabled: true,
        googleAutocompleteEnabled: true,
        rewindEnabled: true,
        contractsEnabled: false,
      }),
    ),
  ),

  // Bot admin endpoints
  http.get(API("/admin/guilds"), requireAuth(() => HttpResponse.json(adminGuilds))),

  http.patch(
    API("/admin/guilds/[^/]+/features"),
    requireAuth(async ({ request, params }) => {
      const guildId = (params as Record<string, string>)["0"] ?? guild.id;
      const body = (await request.json()) as Partial<AdminGuild>;
      const row = adminGuilds.find((g) => g.guildId === guildId);
      if (!row) return new HttpResponse(null, { status: 404 });
      Object.assign(row, body);
      return HttpResponse.json(row);
    }),
  ),

  // Admin monitor endpoints — read-only dashboard data
  http.get(
    API("/admin/health"),
    requireAuth(() =>
      HttpResponse.json({
        ...adminHealthStore,
        syncedAt: new Date().toISOString(),
      }),
    ),
  ),

  http.get(API("/admin/jobs"), requireAuth(() => HttpResponse.json(adminJobsStore))),

  http.get(
    API("/admin/activity"),
    requireAuth(({ request }) => {
      const guildId = new URL(request.url).searchParams.get("guildId");
      const filtered = guildId
        ? adminActivityStore.filter((a) => a.guildId === guildId)
        : adminActivityStore;
      return HttpResponse.json(filtered);
    }),
  ),

  // Admin lifecycle — events with their lifecycle history + replay
  http.get(
    API("/admin/events"),
    requireAuth(({ request }) => {
      const guildId = new URL(request.url).searchParams.get("guildId");
      if (!guildId) return new HttpResponse(null, { status: 400 });
      return HttpResponse.json(adminEventsByGuild[guildId] ?? []);
    }),
  ),

  http.post(
    API("/admin/replay"),
    requireAuth(async ({ request }) => {
      const body = (await request.json()) as {
        eventId: string;
        lifecycleEventType: string;
        skipSideEffects?: boolean;
      };
      // Find the event in any guild's roster — the modal scopes to active guild but the
      // replay endpoint itself is global.
      let found = false;
      for (const list of Object.values(adminEventsByGuild)) {
        if (list.some((e) => e.id === body.eventId)) {
          found = true;
          break;
        }
      }
      if (!found) return new HttpResponse(null, { status: 404 });
      // Pretend the dispatcher noted these listeners — the count varies by trigger but the UI
      // just renders whatever we send back, so a lightweight stub is fine.
      const listenerByTrigger: Record<string, string[]> = {
        EventCreated: ["DiscordChannelInitListener"],
        EventChannelReady: ["DiscordRolesInitListener"],
        EventRolesReady: ["EventClassifyListener"],
        EventClassified: ["EventInitCompleteListener"],
        EventPlanned: [],
        EventPreNotifyDue: ["PreEventNotificationListener"],
        EventPreNotified: ["ImmichAlbumPrepListener"],
        EventCompletionDue: ["EventCompleteListener"],
        EventCompleted: ["ImmichAlbumPostListener"],
        EventArchivalDue: ["EventArchiveListener"],
        EventCancelRequested: ["EventCancelListener"],
        EventDeleteRequested: ["EventDeleteListener"],
      };
      const listeners = listenerByTrigger[body.lifecycleEventType] ?? [];
      return HttpResponse.json({
        message: `queued · ${listeners.length} listener(s) will re-dispatch on commit`,
        listeners,
      });
    }),
  ),

  // Global event-creation toggle (a maintenance flag, not per-guild)
  http.get(
    API("/admin/event-creation"),
    requireAuth(() => HttpResponse.json({ enabled: getEventCreationEnabled() })),
  ),
  http.post(
    API("/admin/event-creation/enable"),
    requireAuth(() => {
      setEventCreationEnabledMock(true);
      return new HttpResponse(null, { status: 204 });
    }),
  ),
  http.post(
    API("/admin/event-creation/disable"),
    requireAuth(() => {
      setEventCreationEnabledMock(false);
      return new HttpResponse(null, { status: 204 });
    }),
  ),
];
