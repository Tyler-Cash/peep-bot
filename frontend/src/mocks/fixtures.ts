import type {
  Attendee,
  EventDetailDto,
  Guild,
  RewindStats,
  UserInfo,
} from "@/lib/types";

type Seed = { name: string; hue: string; snowflake: string };

const seedPeople: Seed[] = [
  { name: "Mira", hue: "#D08A42", snowflake: "1001" },
  { name: "Otis", hue: "#6AAE3E", snowflake: "1002" },
  { name: "Lena", hue: "#A2527F", snowflake: "1003" },
  { name: "Bas", hue: "#357078", snowflake: "1004" },
  { name: "Nim", hue: "#9B8A4A", snowflake: "1005" },
  { name: "Koa", hue: "#8E6E3E", snowflake: "1006" },
  { name: "Wren", hue: "#5C8A44", snowflake: "1007" },
  { name: "Suki", hue: "#E9A2B0", snowflake: "1008" },
  { name: "Tobs", hue: "#C16737", snowflake: "1009" },
  { name: "Cal", hue: "#4A663B", snowflake: "1010" },
];

const att = (s: Seed): Attendee => ({
  snowflake: s.snowflake,
  name: s.name,
  instant: new Date().toISOString(),
  hue: s.hue,
  avatarUrl: null,
});

const by = (name: string) => att(seedPeople.find((p) => p.name === name)!);
const host = (name: string) => {
  const p = seedPeople.find((x) => x.name === name)!;
  return { host: p.name, hostAvatarUrl: null, hostHue: p.hue };
};

// Tiny seeded RNG so names→dates are deterministic across reloads within a day
// (seed changes daily so the feed feels fresh but a single session is stable).
function makeRng(seed: number) {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 0xffffffff;
  };
}

const daySeed = Math.floor(Date.now() / 86_400_000);
const rng = makeRng(daySeed);

// Spread N events across roughly the next 8 weeks at realistic times.
function randomFutureTimes(count: number): string[] {
  const TIME_BUCKETS = [
    { h: 10, m: 30 }, // brunch
    { h: 12, m: 0 },
    { h: 13, m: 0 },
    { h: 14, m: 30 },
    { h: 17, m: 0 }, // early evening
    { h: 18, m: 30 },
    { h: 19, m: 0 },
    { h: 19, m: 45 },
    { h: 20, m: 15 },
    { h: 7, m: 30 }, // early morning for hikes
  ];
  const now = Date.now();
  const offsets: number[] = [];
  for (let i = 0; i < count; i++) {
    // pick a day 2–56 days out, roughly evenly spaced with some jitter
    const base = 2 + Math.floor((i / count) * 54);
    const jitter = Math.floor(rng() * 6) - 3;
    offsets.push(Math.max(1, base + jitter));
  }
  offsets.sort((a, b) => a - b);
  return offsets.map((days) => {
    const bucket = TIME_BUCKETS[Math.floor(rng() * TIME_BUCKETS.length)];
    const d = new Date(now + days * 86_400_000);
    d.setHours(bucket.h, bucket.m, 0, 0);
    return d.toISOString();
  });
}

const times = randomFutureTimes(6);

export const currentUser: UserInfo = {
  username: "otis",
  displayName: "Otis",
  discordId: "1002",
  admin: true,
  avatarUrl: null,
};

export const guild: Guild = {
  id: "mockguild-1",
  name: "porch pigeons",
  initials: "PP",
  iconUrl: null,
  color: "#7BC24F",
  channel: "outings",
  members: 27,
  active: true,
  primaryLocationLat: null as number | null,
  primaryLocationLng: null as number | null,
};

export const guildSettings = {
  primaryLocationPlaceId: null as string | null,
  primaryLocationName: null as string | null,
  primaryLocationLat: null as number | null,
  primaryLocationLng: null as number | null,
};

// In-memory store — mutated by handlers so RSVPs + create-event persist across navigations.
export const store = {
  events: [
    {
      id: "1",
      name: "pub quiz at the glass barrel",
      category: "trivia",
      state: "ACTIVE",
      hasPrivateChannel: false,
      completed: false,
      description:
        "booking is under Mira. arrive by 6:45 or they pull the table. last round is always music — please someone who is not me take the pen.",
      dateTime: times[0],
      ...host("Mira"),
      location: "The Glass Barrel, 44 Brunswick St, Fitzroy",
      capacity: 0,
      accepted: ["Mira", "Otis", "Lena", "Nim", "Koa", "Suki"].map(by),
      maybe: ["Bas", "Tobs"].map(by),
      declined: ["Wren"].map(by),
    },
    {
      id: "2",
      name: "late screening of moonshade",
      category: "movie",
      state: "ACTIVE",
      hasPrivateChannel: false,
      completed: false,
      description:
        "the weird new sci-fi one. 7pm showing, lobby at 6:45. ramen across the road after if anyone wants to keep the night going.",
      dateTime: times[1],
      ...host("Otis"),
      location: "Lumen Cinema, 201 Lygon St, Carlton",
      capacity: 0,
      accepted: ["Otis", "Mira", "Bas", "Nim", "Suki", "Cal"].map(by),
      maybe: ["Lena", "Koa", "Wren"].map(by),
      declined: [],
    },
    {
      id: "3",
      name: "standup showcase — six sets in an hour",
      category: "comedy",
      state: "ACTIVE",
      hasPrivateChannel: false,
      completed: false,
      description:
        "new talent night. doors 6:30, show starts 7, out by 8:15. $18 on the door, bring cash if you can.",
      dateTime: times[2],
      ...host("Lena"),
      location: "Basement Bar, 88 Sydney Rd, Brunswick",
      capacity: 40,
      accepted: ["Lena", "Mira", "Otis", "Koa"].map(by),
      maybe: ["Nim", "Suki", "Tobs"].map(by),
      declined: ["Bas", "Wren"].map(by),
    },
    {
      id: "4",
      name: "bas's backyard cookout",
      category: "food",
      state: "ACTIVE",
      hasPrivateChannel: true,
      completed: false,
      description:
        "firing up the grill at 1. bringing the smoked brisket + slaw. nim is doing a lemon tart. byo drinks + anything green.",
      dateTime: times[3],
      ...host("Bas"),
      location: "Bas's place, Northcote",
      capacity: 12,
      accepted: ["Bas", "Nim", "Otis", "Mira", "Suki", "Cal", "Lena"].map(by),
      maybe: ["Koa"].map(by),
      declined: ["Wren", "Tobs"].map(by),
    },
    {
      id: "5",
      name: "great ocean walk — day trip",
      category: "outdoor",
      state: "ACTIVE",
      hasPrivateChannel: false,
      completed: false,
      description:
        "apollo bay → shelly beach section. 22km, about 7 hours moving. leave the city at 6am sharp. pack layers and lunch, water at the halfway hut.",
      dateTime: times[4],
      ...host("Koa"),
      location: "Apollo Bay Trailhead, Great Ocean Walk",
      capacity: 0,
      accepted: ["Koa", "Otis", "Bas", "Cal"].map(by),
      maybe: ["Mira", "Nim"].map(by),
      declined: ["Lena", "Suki"].map(by),
    },
    {
      id: "6",
      name: "mario kart + takeout",
      category: "game",
      state: "ACTIVE",
      hasPrivateChannel: false,
      completed: false,
      description:
        "tournament format, 4 rounds, winner picks the next one. ordering from that dumpling place on the corner around 7.",
      dateTime: times[5],
      ...host("Suki"),
      location: "Suki's apartment, Collingwood",
      capacity: 8,
      accepted: ["Suki", "Otis", "Tobs", "Wren"].map(by),
      maybe: ["Cal", "Lena"].map(by),
      declined: ["Bas"].map(by),
    },
  ] as unknown as (EventDetailDto & { hostHue?: string })[],
  nextId: 7, // integer counter; stringify when assigning to EventDetailDto.id
};

export const rewindStats = (year: number): RewindStats => ({
  year,
  totalEvents: 14,
  totalUniqueAttendees: 10,
  totalRsvps: 162,
  averageGroupSize: 7.2,
  topCategories: [
    { name: "trivia", eventCount: 4, totalAttendees: 48 },
    { name: "outdoor", eventCount: 3, totalAttendees: 24 },
    { name: "food", eventCount: 3, totalAttendees: 42 },
  ],
  topAttendees: seedPeople.slice(0, 6).map((p, i) => ({
    displayName: p.name,
    eventCount: 12 - i,
    avatarUrl: null,
  })),
  topOrganizers: [
    { displayName: "Mira", eventCount: 5, avatarUrl: null },
    { displayName: "Otis", eventCount: 4, avatarUrl: null },
    { displayName: "Bas", eventCount: 3, avatarUrl: null },
  ],
  topSocialPairs: [
    { user1: "Otis", user2: "Mira", sharedEvents: 11 },
    { user1: "Bas", user2: "Nim", sharedEvents: 9 },
  ],
  eventsByMonth: {
    "2026-01": 1, "2026-02": 2, "2026-03": 3, "2026-04": 2,
    "2026-05": 1, "2026-06": 2, "2026-07": 1, "2026-08": 2,
  },
  eventsByDayOfWeek: {
    Monday: 1, Tuesday: 0, Wednesday: 2, Thursday: 3,
    Friday: 4, Saturday: 2, Sunday: 2,
  },
  firstEvent: { id: "1", name: "pub quiz at the glass barrel", dateTime: "2026-01-10T19:00:00Z" },
  lastEvent: { id: "6", name: "mario kart + takeout", dateTime: "2026-12-15T19:00:00Z" },
  totalPlusOneGuests: 5,
  embeddingsAvailable: false,
});

export function findEvent(id: string) {
  return store.events.find((e) => e.id.toString() === id);
}

export function setRsvp(
  eventId: string,
  user: UserInfo,
  status: "going" | "maybe" | "declined" | "none",
) {
  const ev = findEvent(eventId);
  if (!ev) return null;
  const me: Attendee = {
    snowflake: user.discordId,
    name: user.displayName,
    instant: new Date().toISOString(),
    avatarUrl: user.avatarUrl ?? null,
    hue: "#7BC24F",
  };
  const purge = (list: Attendee[]) =>
    list.filter((a) => a.snowflake !== user.discordId);
  ev.accepted = purge(ev.accepted);
  ev.maybe = purge(ev.maybe);
  ev.declined = purge(ev.declined);
  if (status === "going") ev.accepted.push(me);
  else if (status === "maybe") ev.maybe.push(me);
  else if (status === "declined") ev.declined.push(me);
  return ev;
}
