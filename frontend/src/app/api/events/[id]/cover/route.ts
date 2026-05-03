const BACKEND_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080/api";

export async function GET(
  _req: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;

  const upstream = await fetch(
    `${BACKEND_BASE}/events/${encodeURIComponent(id)}/cover`,
  );

  if (!upstream.ok) {
    return new Response(null, { status: upstream.status });
  }

  const ct = upstream.headers.get("content-type") ?? "image/jpeg";
  const body = await upstream.arrayBuffer();

  return new Response(body, {
    headers: {
      "content-type": ct,
      "cache-control": "public, max-age=86400",
    },
  });
}
