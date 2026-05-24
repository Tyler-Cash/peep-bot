import { registerOTel } from "@vercel/otel";
import { PUBLIC_OTLP_ENDPOINT } from "@/lib/otel/endpoint";
import { SERVICE_NAME } from "@/lib/otel/service";
import { backendOriginRegex } from "@/lib/otel/backendOrigin";

// The OTLP endpoint and protocol are not secret, so they live in source. The
// ONLY secret is the ingest auth header (OTEL_EXPORTER_OTLP_HEADERS), set in the
// Vercel project env. Defaults are applied on Vercel only, so a local
// `npm run dev` doesn't ship traces unless you opt in by setting the env vars.
export function register() {
  if (process.env.VERCEL) {
    process.env.OTEL_EXPORTER_OTLP_ENDPOINT ||= PUBLIC_OTLP_ENDPOINT;
    process.env.OTEL_EXPORTER_OTLP_PROTOCOL ||= "http/protobuf";
  }

  // @vercel/otel only propagates trace context to Vercel deployment URLs by
  // default. Allowlist the backend origin so server-side fetches from the BFF
  // route handlers (avatar, places, gallery, cover) carry the inbound
  // traceparent through to the backend, joining one trace instead of starting
  // a fresh one. Inbound continuation is already on via the default W3C propagator.
  const backend = backendOriginRegex();

  registerOTel({
    serviceName: SERVICE_NAME,
    instrumentationConfig: {
      fetch: backend ? { propagateContextUrls: [backend] } : {},
    },
  });
}
