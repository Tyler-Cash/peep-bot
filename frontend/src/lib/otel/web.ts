import {
  WebTracerProvider,
  BatchSpanProcessor,
  StackContextManager,
} from "@opentelemetry/sdk-trace-web";
import type { Span, SpanProcessor } from "@opentelemetry/sdk-trace-base";
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
import { UserInteractionInstrumentation } from "@opentelemetry/instrumentation-user-interaction";

let started = false;

/**
 * Real-user-monitoring tracing in the browser. Produces:
 *   - a span per backend/API fetch (timing, URL, status),
 *   - a document-load span (navigation + resource timing) per page, and
 *   - a span per user click, which parents the fetches it triggers — so a trace
 *     reads as "user clicked X → these API calls happened", not bare requests.
 *
 * Every span is stamped with `session.id` (groups a tab's whole session) and
 * `page.route`, so Tempo can filter by page and follow a session end to end.
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
      // Enrich first so the attributes are present when BatchSpanProcessor exports.
      new SessionRouteProcessor(),
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
      // Click spans parent the fetches a click triggers (synchronous handlers).
      new UserInteractionInstrumentation({ eventNames: ["click"] }),
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

/**
 * Stamps `session.id` and `page.route` onto every span at start, so a whole
 * user session and per-page traffic are filterable in Tempo. Uses pathname only
 * (not the full URL) to keep query-string params out of trace attributes.
 */
class SessionRouteProcessor implements SpanProcessor {
  onStart(span: Span): void {
    span.setAttribute("session.id", sessionId());
    span.setAttribute("page.route", window.location.pathname);
  }
  onEnd(): void {}
  forceFlush(): Promise<void> {
    return Promise.resolve();
  }
  shutdown(): Promise<void> {
    return Promise.resolve();
  }
}

let cachedSessionId: string | undefined;

/** Per-tab id, stable across reloads within the tab; best-effort if storage is blocked. */
function sessionId(): string {
  if (cachedSessionId) return cachedSessionId;
  try {
    const key = "peepbot.otel.sid";
    let id = window.sessionStorage.getItem(key);
    if (!id) {
      id = crypto.randomUUID();
      window.sessionStorage.setItem(key, id);
    }
    cachedSessionId = id;
    return id;
  } catch {
    cachedSessionId = "unknown";
    return cachedSessionId;
  }
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
