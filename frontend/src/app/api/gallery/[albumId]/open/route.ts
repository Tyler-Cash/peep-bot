import { cookies } from "next/headers";

const BACKEND_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080/api";

// One week — kept in sync with GalleryController.USER_SHARE_TTL on the backend.
const SHARE_TTL_SECONDS = 60 * 60 * 24 * 7;

/**
 * Per-user gallery album entry point. The Spring backend resolves a fresh
 * Immich share link for (user, album) and returns it as JSON; this BFF route
 * 302-redirects the browser to it and applies `Cache-Control: private,
 * max-age=1week` so the browser caches the redirect and doesn't keep regenerating
 * shares. Living on Vercel means the response is also eligible for the edge
 * CDN's per-user cache infra (the Spring backend has no CDN of its own).
 */
export async function GET(
  _req: Request,
  { params }: { params: Promise<{ albumId: string }> },
) {
  const { albumId } = await params;
  const safeAlbumId = encodeURIComponent(albumId);

  const cookieStore = await cookies();
  const sessionValue = cookieStore.get("SESSION")?.value;
  if (!sessionValue) {
    return new Response(null, { status: 401 });
  }

  const upstream = await fetch(`${BACKEND_BASE}/gallery/${safeAlbumId}/open`, {
    headers: { cookie: `SESSION=${sessionValue}` },
  });

  if (!upstream.ok) {
    return new Response(null, { status: upstream.status });
  }

  const payload = (await upstream.json()) as { url?: string };
  if (!payload?.url) {
    return new Response(null, { status: 502 });
  }

  return new Response(null, {
    status: 302,
    headers: {
      Location: payload.url,
      "Cache-Control": `private, max-age=${SHARE_TTL_SECONDS}`,
    },
  });
}
