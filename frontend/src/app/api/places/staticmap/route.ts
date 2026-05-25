import { cookies } from "next/headers";

export const runtime = "nodejs";
export const preferredRegion = "syd1";

const STYLES = [
  "feature:poi|visibility:off",
  "feature:transit|visibility:off",
  "element:labels|visibility:off",
  "feature:landscape|element:geometry|color:0xf2efe6",
  "feature:road|element:geometry|color:0xffffff",
  "feature:road|element:geometry.stroke|color:0xdfd6c0",
  "feature:water|element:geometry|color:0xa5d8e0",
  "feature:poi.park|element:geometry|color:0xc8e5b0",
];

const ALLOWED_SIZES = new Set(["52", "72", "180"]);

export async function GET(req: Request) {
  const cookieStore = await cookies();
  const sessionKey = cookieStore.get("SESSION")?.value;
  if (!sessionKey) {
    return new Response(null, { status: 401 });
  }

  const url = new URL(req.url);
  const placeId = url.searchParams.get("placeId");
  const sizeParam = url.searchParams.get("size") ?? "52";
  const zoomParam = url.searchParams.get("zoom") ?? "15";

  if (!placeId) return new Response(null, { status: 400 });
  if (!ALLOWED_SIZES.has(sizeParam)) {
    return new Response(null, { status: 400 });
  }
  const zoom = Math.max(10, Math.min(18, parseInt(zoomParam, 10) || 15));

  const key = process.env.GOOGLE_MAPS_KEY;
  if (!key) return new Response(null, { status: 503 });

  const size = parseInt(sizeParam, 10);
  const params = new URLSearchParams();
  params.set("center", `place_id:${placeId}`);
  params.set("zoom", String(zoom));
  params.set("size", `${size}x${size}`);
  params.set("scale", "2");
  params.set("maptype", "roadmap");
  for (const s of STYLES) params.append("style", s);
  params.set("key", key);

  try {
    const res = await fetch(
      `https://maps.googleapis.com/maps/api/staticmap?${params.toString()}`,
      { cache: "no-store" },
    );
    if (!res.ok) return new Response(null, { status: 502 });
    const buf = await res.arrayBuffer();
    return new Response(buf, {
      status: 200,
      headers: {
        "Content-Type": res.headers.get("Content-Type") ?? "image/png",
        // Edge + browser caching: Google's tile cache is on by default,
        // but adding our own cache header keeps repeat thumbs free for the user.
        "Cache-Control": "private, max-age=86400",
      },
    });
  } catch {
    return new Response(null, { status: 502 });
  }
}
