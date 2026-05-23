import { cookies } from "next/headers";

const BACKEND_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080/api";

export async function GET(
  req: Request,
  { params }: { params: Promise<{ albumId: string }> },
) {
  const { albumId } = await params;
  const safeAlbumId = encodeURIComponent(albumId);

  const cookieStore = await cookies();
  const sessionValue = cookieStore.get("SESSION")?.value;
  if (!sessionValue) {
    return new Response(null, { status: 401 });
  }

  // Forward conditional-request headers from the client so upstream can answer
  // with a cheap 304 instead of streaming bytes when the browser cache is fresh.
  const upstreamHeaders: Record<string, string> = {
    cookie: `SESSION=${sessionValue}`,
  };
  const ifNoneMatch = req.headers.get("if-none-match");
  const ifModifiedSince = req.headers.get("if-modified-since");
  if (ifNoneMatch) upstreamHeaders["if-none-match"] = ifNoneMatch;
  if (ifModifiedSince) upstreamHeaders["if-modified-since"] = ifModifiedSince;

  const upstream = await fetch(`${BACKEND_BASE}/gallery/thumbnail/${safeAlbumId}`, {
    headers: upstreamHeaders,
  });

  // Per-user thumbnail — must never be shared-cached by Vercel's edge or any
  // intermediary. Set `private` regardless of what upstream sends.
  const cacheHeaders = new Headers();
  cacheHeaders.set("cache-control", "private, max-age=86400");
  const etag = upstream.headers.get("etag");
  const lastModified = upstream.headers.get("last-modified");
  if (etag) cacheHeaders.set("etag", etag);
  if (lastModified) cacheHeaders.set("last-modified", lastModified);

  // 304: pass through without a body so the browser keeps its cached copy.
  if (upstream.status === 304) {
    return new Response(null, { status: 304, headers: cacheHeaders });
  }

  if (!upstream.ok || !upstream.body) {
    return new Response(null, { status: upstream.status });
  }

  const contentType = upstream.headers.get("content-type");
  if (contentType) cacheHeaders.set("content-type", contentType);

  return new Response(upstream.body, { status: 200, headers: cacheHeaders });
}
