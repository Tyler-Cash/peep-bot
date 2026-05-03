import type { ZodType } from "zod";
import { clearSwrCache } from "./swrCache";
import { noteAuthFailure, noteAuthSuccess } from "./authLoopGuard";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "/api";
const MODE = process.env.NEXT_PUBLIC_API_MODE ?? "mock";

let csrfToken: string | null = null;

export class BackendUnreachable extends Error {
  constructor() {
    super("backend unreachable");
  }
}

export class UnauthorizedError extends Error {
  constructor() {
    super("unauthorized");
  }
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: unknown,
    message: string,
  ) {
    super(message);
  }
}

export class ResponseShapeError extends Error {
  constructor(
    public readonly path: string,
    public readonly issues: unknown,
    public readonly received: unknown,
  ) {
    super(
      `${path}: response shape did not match the expected schema. ${JSON.stringify(issues)}`,
    );
  }
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
    throw new BackendUnreachable();
  }
  if (res.status === 429 && attempt < 2) {
    const retry = Number(res.headers.get("Retry-After") ?? "1") * 1000;
    await new Promise((r) => setTimeout(r, retry));
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
    throw new BackendUnreachable();
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
    if (retries >= 2) {
      const body = await readErrorBody(res);
      throw new ApiError(
        429,
        body,
        messageFromBody(body, "too many requests, please try again shortly"),
      );
    }
    const retry = Number(res.headers.get("Retry-After") ?? "1") * 1000;
    await new Promise((r) => setTimeout(r, retry));
    return apiFetchInner<T>(path, init, retries + 1, schema);
  }
  if (!res.ok) {
    const body = await readErrorBody(res);
    throw new ApiError(
      res.status,
      body,
      messageFromBody(body, `request failed (${res.status})`),
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
