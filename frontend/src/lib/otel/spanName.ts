// Pure helpers for naming browser fetch spans. Kept free of browser/OTel imports
// so they're unit-testable in the node test environment.

/**
 * Collapse high-cardinality path segments — UUIDs and snowflake/long numeric
 * ids — to `{id}`, so fetch span names (and the Tempo `span_name` metric label)
 * stay low-cardinality and readable. e.g. `/api/event/cb02…` -> `/api/event/{id}`.
 */
export function templatePath(pathname: string): string {
  return pathname
    .replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi, "{id}")
    .replace(/\/\d{4,}(?=\/|$)/g, "/{id}");
}

/**
 * Build a useful fetch span name like `GET /api/event/{id}` instead of the OTel
 * fetch instrumentation's default `HTTP GET`.
 */
export function fetchSpanName(method: string | undefined, pathname: string): string {
  return `${(method ?? "GET").toUpperCase()} ${templatePath(pathname)}`;
}
