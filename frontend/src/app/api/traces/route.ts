import { otlpEndpoint, otlpHeaders } from "@/lib/otel/endpoint";

export const runtime = "nodejs";
export const preferredRegion = "syd1";

const FORWARD_TIMEOUT_MS = 8000;

// Telemetry ingest must never be cached. Overrides Next.js's default dynamic
// header (public, max-age=0, must-revalidate) with explicit intent.
const NO_STORE = { "Cache-Control": "no-store" };

// Largest clock offset we'll act on (10 min). Beyond this the client clock is so
// far off — or the value is hostile — that shifting would do more harm than good.
const MAX_OFFSET_MS = 10 * 60 * 1000;

// Server-clock source for browser↔server skew estimation. The browser
// (lib/otel/web.ts syncClock) round-trips this to compute its offset, then stamps
// it on each span as `browser.clock.offset_ms` for POST below to apply. Excluded
// from fetch instrumentation client-side, so this GET never produces a span.
export async function GET(): Promise<Response> {
  return Response.json({ now: Date.now() }, { headers: { ...NO_STORE } });
}

// Collector proxy for browser RUM spans. The browser exporter posts OTLP/HTTP
// JSON here — same-origin, so no CORS and (crucially) no Tempo credential in the
// client bundle. This server route forwards to the real collector with the
// ingest auth header that only exists server-side (OTEL_EXPORTER_OTLP_HEADERS).
//
// On a failed forward it surfaces the upstream status (instead of an opaque 502)
// and logs detail to the function log, so a misconfig — e.g. a 401 because the
// auth header is missing or wrong — is diagnosable from the network tab.
export async function POST(req: Request): Promise<Response> {
  const raw = await req.arrayBuffer();
  if (raw.byteLength === 0) return new Response(null, { status: 204, headers: { ...NO_STORE } });

  const contentType = req.headers.get("content-type") ?? "application/json";
  // The browser exporter sends OTLP/HTTP JSON; shift each span's timestamps by its
  // self-reported `browser.clock.offset_ms` so browser spans align with backend spans
  // in Tempo. Anything that isn't the JSON shape we expect is forwarded untouched —
  // telemetry delivery must never depend on this correction succeeding.
  const body: BodyInit = contentType.includes("json")
    ? correctClockSkew(new TextDecoder().decode(raw))
    : raw;

  const target = `${otlpEndpoint()}/v1/traces`;
  const headers: Record<string, string> = {
    "Content-Type": contentType,
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
    if (upstream.ok) return new Response(null, { status: 204, headers: { ...NO_STORE } });

    const detail = (await upstream.text().catch(() => "")).slice(0, 300);
    console.error(
      `[otel-proxy] ${target} -> ${upstream.status} (auth header ${"Authorization" in headers ? "present" : "MISSING"}): ${detail}`,
    );
    return new Response(null, {
      status: upstream.status,
      headers: { ...NO_STORE, "x-otlp-upstream-status": String(upstream.status) },
    });
  } catch (err) {
    console.error(`[otel-proxy] ${target} forward failed:`, err);
    return new Response(null, {
      status: 502,
      headers: { ...NO_STORE, "x-otlp-error": err instanceof Error ? err.name : "error" },
    });
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Rewrites OTLP/HTTP JSON span timestamps by each span's `browser.clock.offset_ms`
 * attribute (set in lib/otel/web.ts), bringing browser-clock spans into server time
 * so the Tempo waterfall lines up with backend spans. BigInt math preserves
 * nanosecond precision (epoch nanos exceed Number's safe-integer range). Returns the
 * input unchanged if it isn't the shape we expect or no span carries an offset.
 */
function correctClockSkew(jsonText: string): string {
  let payload: unknown;
  try {
    payload = JSON.parse(jsonText);
  } catch {
    return jsonText;
  }
  const resourceSpans = (payload as { resourceSpans?: unknown })?.resourceSpans;
  if (!Array.isArray(resourceSpans)) return jsonText;

  let mutated = false;
  for (const rs of resourceSpans) {
    for (const ss of asArray((rs as Record<string, unknown>)?.scopeSpans)) {
      for (const span of asArray((ss as Record<string, unknown>)?.spans)) {
        const s = span as Record<string, unknown>;
        const offsetMs = readOffsetMs(s.attributes);
        if (offsetMs === null || offsetMs === 0 || Math.abs(offsetMs) > MAX_OFFSET_MS) continue;
        const offsetNanos = BigInt(offsetMs) * BigInt(1_000_000);
        s.startTimeUnixNano = shiftNanos(s.startTimeUnixNano, offsetNanos);
        s.endTimeUnixNano = shiftNanos(s.endTimeUnixNano, offsetNanos);
        for (const ev of asArray(s.events)) {
          const e = ev as Record<string, unknown>;
          e.timeUnixNano = shiftNanos(e.timeUnixNano, offsetNanos);
        }
        mutated = true;
      }
    }
  }
  return mutated ? JSON.stringify(payload) : jsonText;
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function shiftNanos(value: unknown, offsetNanos: bigint): unknown {
  if (typeof value !== "string" && typeof value !== "number") return value;
  try {
    return (BigInt(value) + offsetNanos).toString();
  } catch {
    return value;
  }
}

/** Reads the numeric `browser.clock.offset_ms` from an OTLP attribute list. */
function readOffsetMs(attributes: unknown): number | null {
  if (!Array.isArray(attributes)) return null;
  for (const attr of attributes) {
    const a = attr as { key?: unknown; value?: Record<string, unknown> };
    if (a?.key !== "browser.clock.offset_ms") continue;
    const v = a.value ?? {};
    const rawValue = v.intValue ?? v.doubleValue;
    if (rawValue == null) return null;
    const n = Number(rawValue);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}
