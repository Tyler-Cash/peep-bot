/**
 * Registers the trace service worker (public/otel-trace-sw.js). It injects a W3C
 * `traceparent` on browser-initiated, same-origin /api requests the page can't
 * add headers to — `<img>` and SVG `<image>` loads to the BFF (avatars, gallery
 * thumbnails) — so those reach the backend with a traceparent too. Programmatic
 * fetch/XHR already carry one (via @opentelemetry/instrumentation-fetch) and are
 * passed through untouched.
 *
 * Gated like RUM tracing (NEXT_PUBLIC_OTEL_BROWSER_ENABLED) and skipped in mock
 * mode so it never competes with MSW's service worker.
 */
export function registerTraceServiceWorker(): void {
  if (typeof window === "undefined") return;
  if (process.env.NEXT_PUBLIC_OTEL_BROWSER_ENABLED !== "1") return;
  if ((process.env.NEXT_PUBLIC_API_MODE ?? "mock") === "mock") return;
  if (!("serviceWorker" in navigator)) return;
  navigator.serviceWorker.register("/otel-trace-sw.js").catch(() => {
    // Best-effort; a failed registration must never surface to the user.
  });
}
