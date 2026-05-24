import type { ZodType } from "zod";
import { clearSwrCache } from "./swrCache";
import { noteAuthFailure, noteAuthSuccess } from "./authLoopGuard";
import { notifyRateLimited } from "./rateLimitNotifier";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "/api";
const MODE = process.env.NEXT_PUBLIC_API_MODE ?? "mock";

let csrfToken: string | null = null;

/**
 * The minimum a developer needs to find the exact failed request in logs/traces.
 * `traceId` is the OpenTelemetry trace id the backend stamps onto every error body;
 * it correlates directly to logs and traces in Grafana. It (and `status`) are absent
 * for {@link BackendUnreachable}, which never reached the server.
 */
export type ErrorRef = {
  traceId?: string;
  status?: number;
  method: string;
  path: string;
  timestamp: string;
};

function traceIdFromBody(body: unknown): string | undefined {
  if (body && typeof body === "object") {
    const v = (body as Record<string, unknown>).traceId;
    if (typeof v === "string" && v && v !== "unknown") return v;
  }
  return undefined;
}

function timestampFromBody(body: unknown): string | undefined {
  if (body && typeof body === "object") {
    const v = (body as Record<string, unknown>).timestamp;
    if (typeof v === "string" && v) return v;
  }
  return undefined;
}

type RequestRef = { method?: string; path?: string };

export class BackendUnreachable extends Error {
  readonly method: string;
  readonly path: string;
  readonly timestamp: string;
  constructor(ref: RequestRef = {}) {
    super("backend unreachable");
    this.method = ref.method ?? "GET";
    this.path = ref.path ?? "";
    this.timestamp = new Date().toISOString();
  }
}

export class UnauthorizedError extends Error {
  constructor() {
    super("unauthorized");
  }
}

export class ApiError extends Error {
  readonly traceId?: string;
  readonly method: string;
  readonly path: string;
  readonly timestamp: string;
  constructor(
    public readonly status: number,
    public readonly body: unknown,
    message: string,
    ref: RequestRef = {},
  ) {
    super(message);
    this.method = ref.method ?? "GET";
    this.path = ref.path ?? "";
    this.traceId = traceIdFromBody(body);
    this.timestamp = timestampFromBody(body) ?? new Date().toISOString();
  }
}

export class ResponseShapeError extends Error {
  readonly timestamp: string;
  constructor(
    public readonly path: string,
    public readonly issues: unknown,
    public readonly received: unknown,
  ) {
    super(
      `${path}: response shape did not match the expected schema. ${JSON.stringify(issues)}`,
    );
    this.timestamp = new Date().toISOString();
  }
}

/** Pulls an {@link ErrorRef} off any known error type, or null for unknown errors. */
export function errorRef(e: unknown): ErrorRef | null {
  if (e instanceof ApiError) {
    return {
      traceId: e.traceId,
      status: e.status,
      method: e.method,
      path: e.path,
      timestamp: e.timestamp,
    };
  }
  if (e instanceof BackendUnreachable) {
    return { method: e.method, path: e.path, timestamp: e.timestamp };
  }
  if (e instanceof ResponseShapeError) {
    return { method: "GET", path: e.path, timestamp: e.timestamp };
  }
  return null;
}

/**
 * The single place that maps a thrown error to a user-facing message + trace ref.
 * Callers that hit auth failures should `return` before reaching here — by the time an
 * {@link UnauthorizedError} is thrown the api layer has already redirected to /login.
 */
export function describeError(e: unknown): { message: string; ref: ErrorRef | null } {
  if (e instanceof BackendUnreachable) {
    return {
      message: "can't reach the server — check your connection and try again",
      ref: errorRef(e),
    };
  }
  if (e instanceof ResponseShapeError) {
    return { message: "got an unexpected response from the server", ref: errorRef(e) };
  }
  if (e instanceof ApiError) {
    if (e.status === 429) {
      return {
        message: "too many requests — please wait a moment and try again",
        ref: errorRef(e),
      };
    }
    if (e.status >= 500) {
      return { message: "something went wrong — please try again", ref: errorRef(e) };
    }
    return { message: e.message, ref: errorRef(e) };
  }
  return {
    message: e instanceof Error && e.message ? e.message : "something went wrong",
    ref: null,
  };
}

async function readErrorBody(res: Response): Promise<unknown> {
  const text = await res.text().catch(() => "");
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function messageFromBody(body: unknown, fallback: string): string {
  if (body && typeof body === "object") {
    const b = body as Record<string, unknown>;
    for (const key of ["message", "error", "detail"] as const) {
      const v = b[key];
      if (typeof v === "string" && v.trim()) return v;
    }
  }
  if (typeof body === "string" && body.trim()) return body;
  return fallback;
}

async function getCsrf(attempt = 0): Promise<string> {
  if (csrfToken) return csrfToken;
  let res: Response;
  try {
    res = await fetch(`${API_BASE}/csrf`, { credentials: "include" });
  } catch {
    throw new BackendUnreachable({ method: "GET", path: "/csrf" });
  }
  if (res.status === 429 && attempt < 2) {
    const retrySec = Number(res.headers.get("Retry-After") ?? "1");
    notifyRateLimited(retrySec);
    await new Promise((r) => setTimeout(r, retrySec * 1000));
    return getCsrf(attempt + 1);
  }
  if (!res.ok) throw new Error(`csrf fetch failed: ${res.status}`);
  const body = (await res.json()) as { token: string };
  csrfToken = body.token;
  return csrfToken;
}

export async function apiFetch<T>(
  path: string,
  init: RequestInit = {},
  schema?: ZodType<T>,
): Promise<T> {
  return apiFetchInner<T>(path, init, 0, schema);
}

async function apiFetchInner<T>(
  path: string,
  init: RequestInit,
  retries: number,
  schema?: ZodType<T>,
): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (method !== "GET" && method !== "HEAD") {
    try {
      headers.set("X-XSRF-TOKEN", await getCsrf());
    } catch (e) {
      if (MODE === "mock") {
        // Swallow in mock mode — csrf endpoint may not be implemented
      } else {
        throw e;
      }
    }
  }

  let res: Response;
  try {
    res = await fetch(`${API_BASE}${path}`, {
      ...init,
      method,
      headers,
      credentials: "include",
    });
  } catch {
    throw new BackendUnreachable({ method, path });
  }

  if (res.status === 401 || res.status === 403) {
    if (typeof window !== "undefined" && MODE === "live") {
      const onLogin = window.location.pathname.startsWith("/login");
      if (!onLogin) {
        // Stale client-side auth state (persisted SWR cache, csrf token) is
        // what causes /login to bounce us straight back to /. Wipe it before
        // navigating so /login renders against a clean slate.
        clearSwrCache();
        csrfToken = null;
        noteAuthFailure();
        window.location.href = "/login";
      }
    }
    throw new UnauthorizedError();
  }
  if (res.ok && path === "/auth/is-logged-in") {
    noteAuthSuccess();
  }
  if (res.status === 429) {
    const retrySec = Number(res.headers.get("Retry-After") ?? "1");
    notifyRateLimited(retrySec);
    if (retries >= 2) {
      const body = await readErrorBody(res);
      throw new ApiError(
        429,
        body,
        messageFromBody(body, "too many requests, please try again shortly"),
        { method, path },
      );
    }
    await new Promise((r) => setTimeout(r, retrySec * 1000));
    return apiFetchInner<T>(path, init, retries + 1, schema);
  }
  if (!res.ok) {
    const body = await readErrorBody(res);
    throw new ApiError(
      res.status,
      body,
      messageFromBody(body, `request failed (${res.status})`),
      { method, path },
    );
  }
  if (res.status === 204) return undefined as T;
  const data = await res.json();
  if (schema) {
    const parsed = schema.safeParse(data);
    if (!parsed.success) {
      throw new ResponseShapeError(path, parsed.error.issues, data);
    }
    return parsed.data;
  }
  return data as T;
}

export const api = {
  mode: MODE as "mock" | "live",
  base: API_BASE,
  invalidateCsrf: () => {
    csrfToken = null;
  },
};
