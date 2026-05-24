import { cookies } from "next/headers";
import { checkPlacesRateLimit } from "@/lib/rateLimiter";

export const runtime = "nodejs";
export const preferredRegion = "syd1";

type GoogleSuggestion = {
  placePrediction?: {
    placeId: string;
    structuredFormat?: {
      mainText?: { text: string };
      secondaryText?: { text: string };
    };
    text?: { text: string };
  };
};

type PlaceResult = { id: string; title: string; subtitle?: string };

const FIELD_MASK = [
  "suggestions.placePrediction.placeId",
  "suggestions.placePrediction.structuredFormat",
  "suggestions.placePrediction.text",
].join(",");

async function callGoogleAutocomplete(
  apiKey: string,
  q: string,
  sessionToken: string,
  lat: number | null,
  lng: number | null,
): Promise<PlaceResult[]> {
  const body: Record<string, unknown> = { input: q, sessionToken };
  if (lat !== null && lng !== null) {
    body.locationBias = {
      circle: {
        center: { latitude: lat, longitude: lng },
        radius: 50000.0,
      },
    };
  }

  const res = await fetch(
    "https://places.googleapis.com/v1/places:autocomplete",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Goog-Api-Key": apiKey,
        "X-Goog-FieldMask": FIELD_MASK,
      },
      body: JSON.stringify(body),
    },
  );

  if (!res.ok) return [];

  const data = (await res.json()) as { suggestions?: GoogleSuggestion[] };
  return (data.suggestions ?? [])
    .map((s) => s.placePrediction)
    .filter((p): p is NonNullable<typeof p> => Boolean(p))
    .map((p) => ({
      id: p.placeId,
      title: p.structuredFormat?.mainText?.text ?? p.text?.text ?? "",
      subtitle: p.structuredFormat?.secondaryText?.text,
    }));
}

export async function GET(req: Request) {
  const cookieStore = await cookies();
  const sessionKey = cookieStore.get("SESSION")?.value;
  if (!sessionKey) {
    return Response.json({ error: "unauthorized" }, { status: 401 });
  }

  const url = new URL(req.url);
  const q = url.searchParams.get("q") ?? "";
  const sessionToken = url.searchParams.get("sessionToken") ?? "";
  const latParam = url.searchParams.get("lat");
  const lngParam = url.searchParams.get("lng");

  if (!q.trim()) {
    return Response.json([]);
  }

  const rateLimit = await checkPlacesRateLimit(sessionKey);
  if (rateLimit.allowed === false) {
    return Response.json(
      { error: "rate limited" },
      {
        status: 429,
        headers: {
          "Retry-After": String(rateLimit.retryAfter),
          "Retry-After-Ms": String(rateLimit.retryAfterMs),
        },
      },
    );
  }

  const key = process.env.GOOGLE_MAPS_KEY;
  if (!key) return Response.json([]);

  let lat: number | null = null;
  let lng: number | null = null;
  if (latParam && lngParam) {
    const parsedLat = parseFloat(latParam);
    const parsedLng = parseFloat(lngParam);
    if (isFinite(parsedLat) && isFinite(parsedLng)) {
      lat = parsedLat;
      lng = parsedLng;
    }
  }

  try {
    const results = await callGoogleAutocomplete(key, q, sessionToken, lat, lng);
    return Response.json(results);
  } catch {
    return Response.json([]);
  }
}
