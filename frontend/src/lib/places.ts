export type PlaceSuggestion = {
  id: string;
  title: string;
  subtitle?: string;
};

export type SearchResult =
  | PlaceSuggestion[]
  | { rateLimited: true; retryAfter: number };

export function suggestionToLocation(s: PlaceSuggestion): string {
  return s.title;
}

const MODE = process.env.NEXT_PUBLIC_API_MODE ?? "mock";

const MOCK_PLACES: PlaceSuggestion[] = [
  {
    id: "m-royal-oak",
    title: "The Royal Oak",
    subtitle: "127 King St, Newtown",
  },
  {
    id: "m-glass-barrel",
    title: "The Glass Barrel",
    subtitle: "44 Brunswick St, Fitzroy",
  },
  { id: "m-lumen", title: "Lumen Cinema", subtitle: "201 Lygon St, Carlton" },
  {
    id: "m-basement",
    title: "Basement Bar",
    subtitle: "88 Sydney Rd, Brunswick",
  },
  {
    id: "m-noodle",
    title: "Little Noodle House",
    subtitle: "14 Victoria St, Richmond",
  },
  {
    id: "m-comedy-store",
    title: "The Comedy Store",
    subtitle: "Entertainment Quarter, Moore Park",
  },
  {
    id: "m-george-cinema",
    title: "George St Cinemas",
    subtitle: "505 George St, Sydney",
  },
  {
    id: "m-bundeena",
    title: "Bundeena Ferry Terminal",
    subtitle: "Brighton St, Bundeena",
  },
  {
    id: "m-apollo",
    title: "Apollo Bay Trailhead",
    subtitle: "Great Ocean Walk, Apollo Bay",
  },
  {
    id: "m-dumpling",
    title: "Dumpling Corner",
    subtitle: "312 Smith St, Collingwood",
  },
  {
    id: "m-pavilion",
    title: "Park Pavilion",
    subtitle: "Princes Park, Carlton North",
  },
  {
    id: "m-ramen",
    title: "Tonkotsu Ramen House",
    subtitle: "77 Little Lonsdale St, CBD",
  },
  {
    id: "m-rooftop",
    title: "Rooftop Cinema",
    subtitle: "Curtin House, Swanston St, CBD",
  },
  {
    id: "m-night-market",
    title: "Queen Vic Night Market",
    subtitle: "513 Elizabeth St, CBD",
  },
  {
    id: "m-botanic",
    title: "Royal Botanic Gardens",
    subtitle: "Birdwood Ave, South Yarra",
  },
  {
    id: "m-pinball",
    title: "The Pinball Room",
    subtitle: "120 Gertrude St, Fitzroy",
  },
  {
    id: "m-beer-garden",
    title: "The Beer Garden",
    subtitle: "190 Gertrude St, Fitzroy",
  },
  {
    id: "m-observatory",
    title: "Melbourne Observatory",
    subtitle: "Birdwood Ave, Melbourne",
  },
];

function mockSearch(query: string): PlaceSuggestion[] {
  const q = query.trim().toLowerCase();
  if (!q) return MOCK_PLACES.slice(0, 6);
  return MOCK_PLACES.filter(
    (p) =>
      p.title.toLowerCase().includes(q) ||
      p.subtitle?.toLowerCase().includes(q),
  ).slice(0, 8);
}

let blockedUntil = 0;

export async function searchPlaces(
  query: string,
  sessionToken: string,
  signal?: AbortSignal,
  locationBias?: { lat: number; lng: number },
): Promise<SearchResult> {
  if (MODE === "mock") return mockSearch(query);
  if (!query.trim()) return [];

  if (Date.now() < blockedUntil) {
    return {
      rateLimited: true,
      retryAfter: Math.ceil((blockedUntil - Date.now()) / 1000),
    };
  }

  try {
    const params = new URLSearchParams({ q: query, sessionToken });
    if (locationBias) {
      params.set("lat", String(locationBias.lat));
      params.set("lng", String(locationBias.lng));
    }
    const res = await fetch(`/api/places/autocomplete?${params}`, { signal });

    if (res.status === 429) {
      const retryAfter = Number(res.headers.get("Retry-After") ?? "1");
      blockedUntil = Date.now() + retryAfter * 1000;
      return { rateLimited: true, retryAfter };
    }

    if (!res.ok) return [];
    return (await res.json()) as PlaceSuggestion[];
  } catch {
    return [];
  }
}

export function fetchPlaceDetails(placeId: string, sessionToken: string): void {
  if (MODE === "mock" || !placeId) return;
  const params = new URLSearchParams({ placeId, sessionToken });
  fetch(`/api/places/details?${params}`).catch(() => {});
}

export function newPlacesSessionToken(): string {
  return crypto.randomUUID();
}

export async function geocodePlace(
  placeId: string,
  sessionToken: string,
): Promise<{ lat: number; lng: number } | null> {
  if (MODE === "mock") return { lat: -37.8136, lng: 144.9631 };
  try {
    const params = new URLSearchParams({ placeId, sessionToken });
    const res = await fetch(`/api/places/geocode?${params}`);
    if (!res.ok) return null;
    return (await res.json()) as { lat: number; lng: number };
  } catch {
    return null;
  }
}
