import type {
  Attendee,
  EventDetailDto,
  GalleryAlbumDto,
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

// Realistic shape: 4 hubs (>20 attendances) anchor the graph, 12 peripheral
// regulars trail off, and average degree lands near n/2 — the same density
// the production graph hits and what stresses the layout algorithm.
function buildSocialGraph(): RewindStats["socialGraph"] {
  const people: { snowflake: string; name: string; eventCount: number }[] = [
    { snowflake: "1001", name: "Mira", eventCount: 78 },
    { snowflake: "1002", name: "Otis", eventCount: 64 },
    { snowflake: "1003", name: "Lena", eventCount: 51 },
    { snowflake: "1004", name: "Bas", eventCount: 42 },
    { snowflake: "1005", name: "Nim", eventCount: 18 },
    { snowflake: "1006", name: "Koa", eventCount: 15 },
    { snowflake: "1007", name: "Wren", eventCount: 14 },
    { snowflake: "1008", name: "Suki", eventCount: 12 },
    { snowflake: "1009", name: "Tobs", eventCount: 11 },
    { snowflake: "1010", name: "Cal", eventCount: 10 },
    { snowflake: "1011", name: "Pim", eventCount: 9 },
    { snowflake: "1012", name: "Ash", eventCount: 8 },
    { snowflake: "1013", name: "Vim", eventCount: 7 },
    { snowflake: "1014", name: "Roe", eventCount: 6 },
    { snowflake: "1015", name: "Joss", eventCount: 5 },
    { snowflake: "1016", name: "Eli", eventCount: 4 },
    // Loose-tail satellites — people who showed up to a handful of events.
    // 2-4 connections each, mostly to peripherals and to one another.
    { snowflake: "1017", name: "Pax", eventCount: 3 },
    { snowflake: "1018", name: "Tav", eventCount: 3 },
    { snowflake: "1019", name: "Kit", eventCount: 3 },
    { snowflake: "1020", name: "Quill", eventCount: 2 },
    { snowflake: "1021", name: "Mox", eventCount: 2 },
    { snowflake: "1022", name: "Bee", eventCount: 2 },
    { snowflake: "1023", name: "Sol", eventCount: 2 },
    { snowflake: "1024", name: "Fen", eventCount: 2 },
    { snowflake: "1025", name: "Jin", eventCount: 1 },
    { snowflake: "1026", name: "Rye", eventCount: 1 },
    { snowflake: "1027", name: "Lux", eventCount: 1 },
    { snowflake: "1028", name: "Ode", eventCount: 1 },
    { snowflake: "1029", name: "Zev", eventCount: 1 },
  ];

  const rawEdges: [string, string, number][] = [
    // Hub ↔ hub — the densely interlinked core
    ["1001", "1002", 56], ["1001", "1003", 48], ["1001", "1004", 41],
    ["1002", "1003", 37], ["1002", "1004", 33], ["1003", "1004", 28],
    // Hubs ↔ peripherals — most regulars share events with the core
    ["1001", "1005", 16], ["1001", "1006", 15], ["1001", "1007", 14],
    ["1001", "1008", 12], ["1001", "1009", 11], ["1001", "1010", 10],
    ["1001", "1011", 9],  ["1001", "1012", 8],
    ["1002", "1005", 14], ["1002", "1006", 13], ["1002", "1008", 11],
    ["1002", "1009", 10], ["1002", "1011", 9],  ["1002", "1013", 7],
    ["1002", "1014", 6],
    ["1003", "1005", 13], ["1003", "1007", 12], ["1003", "1008", 10],
    ["1003", "1010", 9],  ["1003", "1012", 8],  ["1003", "1015", 5],
    ["1004", "1006", 12], ["1004", "1007", 11], ["1004", "1009", 10],
    ["1004", "1010", 9],  ["1004", "1013", 7],  ["1004", "1016", 4],
    // Peripheral ↔ peripheral — friendships outside the hub core
    ["1005", "1006", 8],  ["1005", "1011", 7],  ["1005", "1007", 6],
    ["1006", "1008", 7],  ["1006", "1012", 6],
    ["1007", "1008", 6],  ["1007", "1013", 5],
    ["1008", "1014", 5],
    ["1009", "1010", 7],  ["1009", "1011", 6],  ["1009", "1015", 4],
    ["1010", "1012", 6],  ["1010", "1016", 4],
    ["1011", "1012", 6],  ["1011", "1013", 5],
    ["1012", "1014", 5],
    ["1013", "1015", 4],
    ["1014", "1015", 4],  ["1014", "1016", 3],
    ["1015", "1016", 3],
    // Satellites linking to one another — sparse loose-tail community
    ["1017", "1018", 2], ["1019", "1020", 2], ["1023", "1024", 2],
    ["1027", "1028", 1], ["1028", "1029", 1],
    // Satellites ↔ hubs (each hub gains ~2 new edges)
    ["1001", "1017", 5], ["1001", "1019", 4],
    ["1002", "1018", 5], ["1002", "1021", 3],
    ["1003", "1023", 4], ["1003", "1025", 3],
    ["1004", "1022", 4], ["1004", "1026", 3],
    // Satellites ↔ peripherals (each peripheral gains ~2 new edges)
    ["1005", "1017", 3], ["1005", "1018", 2],
    ["1006", "1017", 3], ["1006", "1019", 2],
    ["1007", "1018", 2], ["1007", "1020", 2],
    ["1008", "1019", 2], ["1008", "1021", 2],
    ["1009", "1020", 2], ["1009", "1022", 2],
    ["1010", "1021", 2], ["1010", "1023", 2],
    ["1011", "1022", 2], ["1011", "1024", 1],
    ["1012", "1023", 2], ["1012", "1025", 1],
    ["1013", "1024", 1], ["1013", "1026", 1],
    ["1014", "1025", 1], ["1014", "1027", 1],
    ["1015", "1026", 1], ["1015", "1028", 1],
    ["1016", "1027", 1], ["1016", "1029", 1],
  ];

  return {
    nodes: people.map((p) => ({
      snowflake: p.snowflake,
      displayName: p.name,
      avatarUrl: null,
      eventCount: p.eventCount,
    })),
    edges: rawEdges.map(([user1Snowflake, user2Snowflake, sharedEvents]) => ({
      user1Snowflake,
      user2Snowflake,
      sharedEvents,
    })),
  };
}

export const rewindStats = (year: number): RewindStats => ({
  year,
  totalEvents: 104,
  totalUniqueAttendees: 29,
  totalRsvps: 748,
  averageGroupSize: 7.2,
  topCategories: [
    { name: "trivia", eventCount: 28, totalAttendees: 196 },
    { name: "food", eventCount: 22, totalAttendees: 176 },
    { name: "outdoor", eventCount: 18, totalAttendees: 108 },
    { name: "game", eventCount: 16, totalAttendees: 128 },
    { name: "movie", eventCount: 12, totalAttendees: 84 },
    { name: "comedy", eventCount: 8, totalAttendees: 56 },
  ],
  topAttendees: seedPeople.map((p, i) => ({
    displayName: p.name,
    eventCount: 82 - i * 6,
    avatarUrl: null,
  })),
  topOrganizers: [
    { displayName: "Mira", eventCount: 31, avatarUrl: null },
    { displayName: "Otis", eventCount: 24, avatarUrl: null },
    { displayName: "Bas", eventCount: 18, avatarUrl: null },
    { displayName: "Suki", eventCount: 14, avatarUrl: null },
    { displayName: "Lena", eventCount: 11, avatarUrl: null },
  ],
  socialGraph: buildSocialGraph(),
  eventsByMonth: {
    [`${year}-01`]: 7, [`${year}-02`]: 8, [`${year}-03`]: 10, [`${year}-04`]: 9,
    [`${year}-05`]: 8, [`${year}-06`]: 11, [`${year}-07`]: 7, [`${year}-08`]: 9,
    [`${year}-09`]: 10, [`${year}-10`]: 9, [`${year}-11`]: 8, [`${year}-12`]: 8,
  },
  eventsByDayOfWeek: {
    Monday: 8, Tuesday: 5, Wednesday: 12, Thursday: 18,
    Friday: 26, Saturday: 22, Sunday: 13,
  },
  firstEvent: { id: "1", name: "pub quiz at the glass barrel", dateTime: `${year}-01-10T19:00:00Z` },
  lastEvent: { id: "6", name: "new year's eve drinks", dateTime: `${year}-12-28T20:00:00Z` },
  totalPlusOneGuests: 5,
  embeddingsAvailable: false,
});

// Past events with photo albums, deterministic per-day so the layout is stable.
// thumbnailUrl uses picsum.photos with a per-album seed so each card gets a
// distinct, realistic-looking image.
function pastDate(daysAgo: number, hour = 19, minute = 0): string {
  const d = new Date(Date.now() - daysAgo * 86_400_000);
  d.setHours(hour, minute, 0, 0);
  return d.toISOString();
}

const galleryThumb = (seed: string) =>
  `https://picsum.photos/seed/${seed}/640/480`;

// Galleries are derived from the canonical events store so that eventId,
// eventName, and attendees on each album match what /events/{id} actually
// renders. dateTime is shifted into the past since albums represent past
// hangouts; everything else is borrowed straight from the source event.
const albumOffsets = [6, 13, 20, 28, 41, 55, 72, 96, 118];
const albumPhotoCounts = [42, 87, 18, 134, 63, 29, 51, 108, 7];
// Mock the album-share permutations the BFF open endpoint produces in prod:
//   - even index → albumUrl points to a stand-in external URL the new tab can
//                  actually load (in prod this would be the BFF /open route
//                  which 302s to Immich; in mock mode there's no session so
//                  hitting that route would 401, hence the direct placeholder)
//   - odd index  → legacy event with no share key → albumUrl null, thumbnail
//                  click falls back to the event-detail page
export const galleryAlbums: GalleryAlbumDto[] = store.events.map((event, i) => ({
  eventId: String(event.id),
  eventName: event.name,
  eventDateTime: pastDate(
    albumOffsets[i] ?? 30 + i * 14,
    19 + ((i * 3) % 5) - 2,
    (i * 15) % 60,
  ),
  albumId: `alb-${event.id}`,
  thumbnailUrl: galleryThumb(`peepo-${event.id}-${event.category ?? "event"}`),
  albumUrl:
    i % 2 === 0
      ? `https://picsum.photos/seed/peepo-share-${event.id}/1600/1000`
      : null,
  assetCount: albumPhotoCounts[i] ?? 12 + i * 7,
  attendees: event.accepted ?? [],
}));

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
