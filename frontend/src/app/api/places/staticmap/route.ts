import { cookies } from "next/headers";
import { checkStaticMapRateLimit } from "@/lib/rateLimiter";
import { resolveDiscordIdFromSession } from "@/lib/userResolver";

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

  // Resolve sessionKey -> discordId for relog-proof rate limiting. If the resolver fails because
  // the backend is unreachable (502), fall back to using the sessionKey itself rather than 502ing
  // the image — the rate-limit is a budget cap, not a security boundary, and a transient backend
  // blip shouldn't break every map thumbnail in the app. A 401 from the resolver does propagate:
  // that means the session is actually invalid.
  const resolved = await resolveDiscordIdFromSession(sessionKey);
  let rateLimitKey: string;
  if ("discordId" in resolved) {
    rateLimitKey = resolved.discordId;
  } else if (resolved.status === 401) {
    return new Response(null, { status: 401 });
  } else {
    console.warn("[staticmap] falling back to session-keyed rate limit");
    rateLimitKey = sessionKey;
  }

  const rateLimit = await checkStaticMapRateLimit(rateLimitKey);
  if (rateLimit.allowed === false) {
    return new Response(null, {
      status: 429,
      headers: {
        "Retry-After": String(rateLimit.retryAfter),
        "Retry-After-Ms": String(rateLimit.retryAfterMs),
      },
    });
  }

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
      {
        // Server-side data cache keyed by URL: two users requesting the same
        // place/size/zoom share one upstream call for 7 days.
        next: { revalidate: 60 * 60 * 24 * 7, tags: ["staticmap"] },
      },
    );
    if (!res.ok) return new Response(null, { status: 502 });
    const buf = await res.arrayBuffer();
    return new Response(buf, {
      status: 200,
      headers: {
        "Content-Type": res.headers.get("Content-Type") ?? "image/png",
        // placeId+size+zoom fully determines the image, so it's safe to mark
        // immutable. Long max-age means the browser never re-fetches the same
        // thumb after the first render.
        "Cache-Control": "private, max-age=604800, immutable",
      },
    });
  } catch {
    return new Response(null, { status: 502 });
  }
}
