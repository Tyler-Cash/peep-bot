import { cookies } from "next/headers";

const BACKEND_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080/api";

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

  const upstream = await fetch(`${BACKEND_BASE}/gallery/thumbnail/${safeAlbumId}`, {
    headers: { cookie: `SESSION=${sessionValue}` },
  });

  if (!upstream.ok || !upstream.body) {
    return new Response(null, { status: upstream.status });
  }

  const headers = new Headers();
  const contentType = upstream.headers.get("content-type");
  if (contentType) headers.set("content-type", contentType);
  // Per-user thumbnail — must never be shared-cached by Vercel's edge or any
  // intermediary. Set `private` here regardless of what upstream sends.
  headers.set("cache-control", "private, max-age=86400");

  return new Response(upstream.body, { status: 200, headers });
}
