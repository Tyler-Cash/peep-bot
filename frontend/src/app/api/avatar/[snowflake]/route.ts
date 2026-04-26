import { cookies } from "next/headers";

const BACKEND_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080/api";

export async function GET(
  _req: Request,
  { params }: { params: Promise<{ snowflake: string }> },
) {
  const { snowflake } = await params;

  const cookieStore = await cookies();
  const sessionValue = cookieStore.get("SESSION")?.value;
  if (!sessionValue) {
    return new Response(null, { status: 401 });
  }

  const upstream = await fetch(`${BACKEND_BASE}/avatar/${snowflake}`, {
    headers: { cookie: `SESSION=${sessionValue}` },
  });

  if (!upstream.ok) {
    return new Response(null, { status: upstream.status });
  }

  const ct = upstream.headers.get("content-type") ?? "image/webp";
  const cacheControl =
    upstream.headers.get("cache-control") ?? "public, max-age=86400";
  const body = await upstream.arrayBuffer();

  return new Response(body, {
    headers: { "content-type": ct, "cache-control": cacheControl },
  });
}
