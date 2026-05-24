// Public OTLP collector (Tempo, fronted by Traefik at otel.tylercash.dev).
// The endpoint is NOT secret — only the ingest auth header is. Shared by the
// server-side @vercel/otel registration (src/instrumentation.ts) and the
// browser-span collector proxy (src/app/api/traces/route.ts). Both run in the
// Node runtime; this module must stay free of browser-only imports.
export const PUBLIC_OTLP_ENDPOINT = "https://otel.tylercash.dev";

/**
 * Base OTLP endpoint. Defaults to the public collector but honors
 * OTEL_EXPORTER_OTLP_ENDPOINT so a self-hosted deploy can point at an internal
 * collector (e.g. http://grafana-lgtm:4318) with no auth.
 */
export function otlpEndpoint(): string {
  return process.env.OTEL_EXPORTER_OTLP_ENDPOINT || PUBLIC_OTLP_ENDPOINT;
}

/**
 * Parse OTEL_EXPORTER_OTLP_HEADERS ("k1=v1,k2=v2") into a header map. Values may
 * contain '=' (e.g. "Authorization=Basic dXNlcjpwYXNz=="), so each pair is split
 * on its first '=' only.
 */
export function otlpHeaders(): Record<string, string> {
  const raw = process.env.OTEL_EXPORTER_OTLP_HEADERS;
  if (!raw) return {};
  const out: Record<string, string> = {};
  for (const pair of raw.split(",")) {
    const eq = pair.indexOf("=");
    if (eq <= 0) continue;
    const key = pair.slice(0, eq).trim();
    const value = pair.slice(eq + 1).trim();
    if (key) out[key] = value;
  }
  return out;
}
