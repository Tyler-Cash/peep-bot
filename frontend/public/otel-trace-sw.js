/* eslint-disable */
// Trace-context service worker.
//
// Injects a W3C `traceparent` on browser-initiated, same-origin /api requests
// that the page cannot add headers to — chiefly <img> and SVG <image> loads to
// the BFF (avatars, gallery thumbnails). Programmatic fetch/XHR already carry a
// traceparent (added by @opentelemetry/instrumentation-fetch) and are passed
// through untouched, so this only fills the gap for asset-style loads.
//
// Defensive by construction: it only acts on same-origin GET requests under
// /api that aren't navigations or the telemetry export, and any failure falls
// back to the original request — telemetry must never break a load.

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));

function randomHex(byteLength) {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  let out = "";
  for (const b of bytes) out += b.toString(16).padStart(2, "0");
  return out;
}

// version "00" - trace-id (16 bytes) - span-id (8 bytes) - flags "01" (sampled)
function newTraceparent() {
  return "00-" + randomHex(16) + "-" + randomHex(8) + "-01";
}

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;
  if (request.mode === "navigate") return; // never touch navigations (e.g. album /open)
  if (request.headers.has("traceparent")) return; // already instrumented (fetch/XHR)

  let url;
  try {
    url = new URL(request.url);
  } catch {
    return;
  }
  if (url.origin !== self.location.origin) return; // only same-origin BFF
  if (!url.pathname.startsWith("/api/")) return;
  if (url.pathname.startsWith("/api/traces")) return; // never trace the telemetry export

  event.respondWith(
    (async () => {
      try {
        const headers = new Headers(request.headers);
        headers.set("traceparent", newTraceparent());
        return await fetch(
          new Request(url.toString(), {
            method: "GET",
            headers,
            credentials: "include",
            mode: "same-origin",
            redirect: request.redirect,
            cache: request.cache,
          }),
        );
      } catch {
        return fetch(request); // telemetry must never break the request
      }
    })(),
  );
});
