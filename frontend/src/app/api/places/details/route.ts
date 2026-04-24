export async function GET(req: Request) {
  const url = new URL(req.url);
  const placeId = url.searchParams.get("placeId");
  const sessionToken = url.searchParams.get("sessionToken") ?? "";

  if (!placeId) return new Response(null, { status: 400 });

  const key = process.env.GOOGLE_MAPS_KEY;
  if (!key) return new Response(null, { status: 204 });

  try {
    const detailUrl = new URL(
      `https://places.googleapis.com/v1/places/${encodeURIComponent(placeId)}`,
    );
    if (sessionToken) detailUrl.searchParams.set("sessionToken", sessionToken);

    await fetch(detailUrl.toString(), {
      headers: {
        "X-Goog-Api-Key": key,
        "X-Goog-FieldMask": "id",
      },
    });
  } catch {
    // fire-and-forget: ignore errors
  }

  return new Response(null, { status: 204 });
}
