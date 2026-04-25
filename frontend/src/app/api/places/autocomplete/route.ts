import { cookies } from "next/headers";
import { checkPlacesRateLimit } from "@/lib/rateLimiter";

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

export async function GET(req: Request) {
  const cookieStore = await cookies();
  const sessionKey = cookieStore.get("SESSION")?.value;
  if (!sessionKey) {
    return Response.json({ error: "unauthorized" }, { status: 401 });
  }

  const url = new URL(req.url);
  const q = url.searchParams.get("q") ?? "";
  const sessionToken = url.searchParams.get("sessionToken") ?? "";

  if (!q.trim()) {
    return Response.json([]);
  }

  const rateLimit = await checkPlacesRateLimit(sessionKey);
  if ("retryAfter" in rateLimit) {
    return Response.json(
      { error: "rate limited" },
      { status: 429, headers: { "Retry-After": String(rateLimit.retryAfter) } },
    );
  }

  const key = process.env.GOOGLE_MAPS_KEY;
  if (!key) return Response.json([]);

  try {
    const res = await fetch(
      "https://places.googleapis.com/v1/places:autocomplete",
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Goog-Api-Key": key,
        },
        body: JSON.stringify({ input: q, sessionToken }),
      },
    );

    if (!res.ok) return Response.json([]);

    const data = (await res.json()) as { suggestions?: GoogleSuggestion[] };

    return Response.json(
      (data.suggestions ?? [])
        .map((s) => s.placePrediction)
        .filter((p): p is NonNullable<typeof p> => Boolean(p))
        .map((p) => ({
          id: p.placeId,
          title: p.structuredFormat?.mainText?.text ?? p.text?.text ?? "",
          subtitle: p.structuredFormat?.secondaryText?.text,
        })),
    );
  } catch {
    return Response.json([]);
  }
}
