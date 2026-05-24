import { otlpEndpoint, otlpHeaders } from "@/lib/otel/endpoint";

export const runtime = "nodejs";
export const preferredRegion = "syd1";

// Collector proxy for browser RUM spans. The browser exporter posts OTLP/HTTP
// JSON here — same-origin, so no CORS and (crucially) no Tempo credential in the
// client bundle. This server route forwards to the real collector with the
// ingest auth header that only exists server-side (OTEL_EXPORTER_OTLP_HEADERS).
export async function POST(req: Request): Promise<Response> {
  const body = await req.arrayBuffer();
  if (body.byteLength === 0) return new Response(null, { status: 204 });

  try {
    const upstream = await fetch(`${otlpEndpoint()}/v1/traces`, {
      method: "POST",
      headers: {
        "Content-Type": req.headers.get("content-type") ?? "application/json",
        ...otlpHeaders(),
      },
      body,
    });
    // Telemetry must never surface to the user; collapse to 204/502.
    return new Response(null, { status: upstream.ok ? 204 : 502 });
  } catch {
    return new Response(null, { status: 502 });
  }
}
