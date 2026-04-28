import { cookies } from "next/headers";

export async function GET(req: Request) {
  const cookieStore = await cookies();
  const sessionKey = cookieStore.get("SESSION")?.value;
  if (!sessionKey) {
    return Response.json({ error: "unauthorized" }, { status: 401 });
  }

  const url = new URL(req.url);
  const placeId = url.searchParams.get("placeId");
  const sessionToken = url.searchParams.get("sessionToken") ?? "";

  if (!placeId) return Response.json({ error: "placeId required" }, { status: 400 });

  const key = process.env.GOOGLE_MAPS_KEY;
  if (!key) return Response.json({ error: "no key" }, { status: 503 });

  try {
    const detailUrl = new URL(
      `https://places.googleapis.com/v1/places/${encodeURIComponent(placeId)}`,
    );
    if (sessionToken) detailUrl.searchParams.set("sessionToken", sessionToken);

    const res = await fetch(detailUrl.toString(), {
      headers: {
        "X-Goog-Api-Key": key,
        "X-Goog-FieldMask": "id,location",
      },
    });

    if (!res.ok) return Response.json({ error: "places error" }, { status: 502 });

    const data = (await res.json()) as {
      location?: { latitude: number; longitude: number };
    };

    if (!data.location) return Response.json({ error: "no location" }, { status: 404 });

    return Response.json({
      lat: data.location.latitude,
      lng: data.location.longitude,
    });
  } catch {
    return Response.json({ error: "fetch failed" }, { status: 502 });
  }
}
