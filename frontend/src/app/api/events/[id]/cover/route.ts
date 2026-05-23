const BACKEND_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080/api";

export async function GET(
  req: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;

  // Intentionally no SESSION check: this route serves the Discord/Slack/etc.
  // link-unfurl scraper, which has no cookie. The backend still auth-gates the
  // bytes for direct in-app fetches via SESSION; this BFF path is the public
  // preview surface. Covers are user-uploaded but considered scrape-safe by
  // design — see EmbedService.java in the backend, which embeds this URL in
  // outbound Discord messages.

  // Forward conditional-request headers so we can return a cheap 304.
  const upstreamHeaders: Record<string, string> = {};
  const ifNoneMatch = req.headers.get("if-none-match");
  const ifModifiedSince = req.headers.get("if-modified-since");
  if (ifNoneMatch) upstreamHeaders["if-none-match"] = ifNoneMatch;
  if (ifModifiedSince) upstreamHeaders["if-modified-since"] = ifModifiedSince;

  const upstream = await fetch(
    `${BACKEND_BASE}/events/${encodeURIComponent(id)}/cover`,
    { headers: upstreamHeaders },
  );

  const cacheHeaders = new Headers();
  cacheHeaders.set("cache-control", "public, max-age=86400");
  const etag = upstream.headers.get("etag");
  const lastModified = upstream.headers.get("last-modified");
  if (etag) cacheHeaders.set("etag", etag);
  if (lastModified) cacheHeaders.set("last-modified", lastModified);

  if (upstream.status === 304) {
    return new Response(null, { status: 304, headers: cacheHeaders });
  }

  if (!upstream.ok) {
    return new Response(null, { status: upstream.status });
  }

  const ct = upstream.headers.get("content-type") ?? "image/jpeg";
  cacheHeaders.set("content-type", ct);
  const body = await upstream.arrayBuffer();
  return new Response(body, { headers: cacheHeaders });
}
