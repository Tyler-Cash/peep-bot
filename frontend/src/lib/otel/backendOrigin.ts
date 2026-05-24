function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/**
 * A regex matching the backend's origin (scheme + host) derived from
 * NEXT_PUBLIC_API_BASE, or null when the base is relative/unset.
 *
 * Used to allowlist the backend for @vercel/otel outbound `traceparent`
 * propagation: @vercel/otel only propagates trace context to Vercel deployment
 * URLs by default, so server-side fetches from route handlers to the backend
 * would otherwise start a fresh trace instead of continuing the current one.
 */
export function backendOriginRegex(
  apiBase: string | undefined = process.env.NEXT_PUBLIC_API_BASE,
): RegExp | null {
  if (!apiBase) return null;
  try {
    const { origin } = new URL(apiBase);
    return new RegExp("^" + escapeRegExp(origin));
  } catch {
    return null; // relative base ("/api") → same-origin, already propagated
  }
}
