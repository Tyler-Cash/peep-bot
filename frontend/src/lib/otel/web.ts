import {
  WebTracerProvider,
  BatchSpanProcessor,
  StackContextManager,
} from "@opentelemetry/sdk-trace-web";
import { OTLPTraceExporter } from "@opentelemetry/exporter-trace-otlp-http";
import { resourceFromAttributes } from "@opentelemetry/resources";
import {
  ATTR_SERVICE_NAME,
  ATTR_SERVICE_VERSION,
  ATTR_DEPLOYMENT_ENVIRONMENT_NAME,
} from "@opentelemetry/semantic-conventions";
import { registerInstrumentations } from "@opentelemetry/instrumentation";
import { FetchInstrumentation } from "@opentelemetry/instrumentation-fetch";
import { DocumentLoadInstrumentation } from "@opentelemetry/instrumentation-document-load";

let started = false;

/**
 * Real-user-monitoring tracing in the browser. Produces:
 *   - a span per backend/API fetch (timing, URL, status), and
 *   - a document-load span (navigation + resource timing) per page.
 *
 * Most importantly, the fetch instrumentation injects a W3C `traceparent` on the
 * cross-origin calls to the backend, which (sampling 1.0, default W3C
 * propagation) the Spring backend continues — so the frontend fetch span and the
 * backend server span share one trace id and render as a single Tempo trace.
 *
 * Spans are exported to the same-origin /api/traces proxy, which forwards them to
 * the collector with the ingest credential server-side — the secret never enters
 * the client bundle. Gated on NEXT_PUBLIC_OTEL_BROWSER_ENABLED so local dev, mock
 * mode, and unconfigured deploys stay silent.
 */
export function initWebTracing(): void {
  if (started || typeof window === "undefined") return;
  if (process.env.NEXT_PUBLIC_OTEL_BROWSER_ENABLED !== "1") return;
  started = true;

  const apiBase = process.env.NEXT_PUBLIC_API_BASE ?? "/api";
  const backendOrigin = safeOrigin(apiBase);

  const provider = new WebTracerProvider({
    resource: resourceFromAttributes({
      [ATTR_SERVICE_NAME]: "peep-bot-frontend",
      [ATTR_SERVICE_VERSION]: process.env.NEXT_PUBLIC_APP_VERSION ?? "dev",
      [ATTR_DEPLOYMENT_ENVIRONMENT_NAME]:
        process.env.NEXT_PUBLIC_VERCEL_ENV ?? "production",
    }),
    spanProcessors: [
      new BatchSpanProcessor(
        new OTLPTraceExporter({
          url: new URL("/api/traces", window.location.origin).toString(),
        }),
      ),
    ],
  });

  // Default propagator is W3C trace-context + baggage — exactly what the backend
  // extracts. StackContextManager keeps span context across sync call stacks.
  provider.register({ contextManager: new StackContextManager() });

  registerInstrumentations({
    instrumentations: [
      new DocumentLoadInstrumentation(),
      new FetchInstrumentation({
        // Never trace the span-export POST itself (would loop), nor Next internals.
        ignoreUrls: [/\/api\/traces$/, /\/_next\//],
        // Inject traceparent on cross-origin backend calls so the backend can
        // continue the trace. Same-origin requests are propagated automatically.
        propagateTraceHeaderCorsUrls: backendOrigin
          ? [new RegExp(escapeRegExp(backendOrigin))]
          : [],
        clearTimingResources: true,
      }),
    ],
  });
}

function safeOrigin(base: string): string | null {
  try {
    return new URL(base, window.location.origin).origin;
  } catch {
    return null;
  }
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
