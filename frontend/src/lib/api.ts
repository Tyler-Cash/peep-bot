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
): Promise<T> {
  return apiFetchInner<T>(path, init, 0);
}

async function apiFetchInner<T>(
  path: string,
  init: RequestInit,
  retries: number,
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
    if (
      typeof window !== "undefined" &&
      MODE === "live" &&
      !window.location.pathname.startsWith("/login")
    ) {
      window.location.href = "/login";
    }
    throw new UnauthorizedError();
  }
  if (res.status === 429) {
    if (retries >= 2) {
      throw new Error(`${method} ${path} rate limited`);
    }
    const retry = Number(res.headers.get("Retry-After") ?? "1") * 1000;
    await new Promise((r) => setTimeout(r, retry));
    return apiFetchInner<T>(path, init, retries + 1);
  }
  if (!res.ok) {
    throw new Error(`api ${method} ${path} failed: ${res.status}`);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const api = {
  mode: MODE as "mock" | "live",
  base: API_BASE,
  invalidateCsrf: () => {
    csrfToken = null;
  },
};
