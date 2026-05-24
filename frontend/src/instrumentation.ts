import { registerOTel } from "@vercel/otel";
import { PUBLIC_OTLP_ENDPOINT } from "@/lib/otel/endpoint";

// The OTLP endpoint and protocol are not secret, so they live in source. The
// ONLY secret is the ingest auth header (OTEL_EXPORTER_OTLP_HEADERS), set in the
// Vercel project env. Defaults are applied on Vercel only, so a local
// `npm run dev` doesn't ship traces unless you opt in by setting the env vars.
export function register() {
  if (process.env.VERCEL) {
    process.env.OTEL_EXPORTER_OTLP_ENDPOINT ||= PUBLIC_OTLP_ENDPOINT;
    process.env.OTEL_EXPORTER_OTLP_PROTOCOL ||= "http/protobuf";
  }
  registerOTel({ serviceName: "peep-bot-frontend" });
}
