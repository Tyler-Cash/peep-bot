import { cookies } from "next/headers";

const BACKEND_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080/api";

export async function GET(
  req: Request,
  { params }: { params: Promise<{ snowflake: string }> },
) {
  const { snowflake } = await params;

  if (!/^[0-9]{17,20}$/.test(snowflake)) {
    return new Response(null, { status: 400 });
  }

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

  const upstream = await fetch(`${BACKEND_BASE}/avatar/${encodeURIComponent(snowflake)}`, {
    headers: upstreamHeaders,
  });

  // Per-user avatar — must never be shared-cached. Respect upstream's cache hint
  // when sent, otherwise default to a private 24h window.
  const cacheHeaders = new Headers();
  cacheHeaders.set(
    "cache-control",
    upstream.headers.get("cache-control") ?? "private, max-age=86400",
  );
  const etag = upstream.headers.get("etag");
  const lastModified = upstream.headers.get("last-modified");
  if (etag) cacheHeaders.set("etag", etag);
  if (lastModified) cacheHeaders.set("last-modified", lastModified);

  // 304: pass through without a body so the browser keeps its cached copy.
  if (upstream.status === 304) {
    return new Response(null, { status: 304, headers: cacheHeaders });
  }

  if (!upstream.ok) {
    return new Response(null, { status: upstream.status });
  }

  const ct = upstream.headers.get("content-type") ?? "image/webp";
  cacheHeaders.set("content-type", ct);
  const body = await upstream.arrayBuffer();
  return new Response(body, { headers: cacheHeaders });
}
