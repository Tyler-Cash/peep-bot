import { otlpEndpoint, otlpHeaders } from "@/lib/otel/endpoint";

export const runtime = "nodejs";
export const preferredRegion = "syd1";

const FORWARD_TIMEOUT_MS = 8000;

// Collector proxy for browser RUM spans. The browser exporter posts OTLP/HTTP
// JSON here — same-origin, so no CORS and (crucially) no Tempo credential in the
// client bundle. This server route forwards to the real collector with the
// ingest auth header that only exists server-side (OTEL_EXPORTER_OTLP_HEADERS).
//
// On a failed forward it surfaces the upstream status (instead of an opaque 502)
// and logs detail to the function log, so a misconfig — e.g. a 401 because the
// auth header is missing or wrong — is diagnosable from the network tab.
export async function POST(req: Request): Promise<Response> {
  const body = await req.arrayBuffer();
  if (body.byteLength === 0) return new Response(null, { status: 204 });

  const target = `${otlpEndpoint()}/v1/traces`;
  const headers: Record<string, string> = {
    "Content-Type": req.headers.get("content-type") ?? "application/json",
    ...otlpHeaders(),
  };

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), FORWARD_TIMEOUT_MS);
  try {
    const upstream = await fetch(target, {
      method: "POST",
      headers,
      body,
      signal: controller.signal,
    });
    if (upstream.ok) return new Response(null, { status: 204 });

    const detail = (await upstream.text().catch(() => "")).slice(0, 300);
    console.error(
      `[otel-proxy] ${target} -> ${upstream.status} (auth header ${"Authorization" in headers ? "present" : "MISSING"}): ${detail}`,
    );
    return new Response(null, {
      status: upstream.status,
      headers: { "x-otlp-upstream-status": String(upstream.status) },
    });
  } catch (err) {
    console.error(`[otel-proxy] ${target} forward failed:`, err);
    return new Response(null, {
      status: 502,
      headers: { "x-otlp-error": err instanceof Error ? err.name : "error" },
    });
  } finally {
    clearTimeout(timer);
  }
}
