const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "/api";
const MODE = process.env.NEXT_PUBLIC_API_MODE ?? "mock";

let csrfToken: string | null = null;

async function getCsrf(): Promise<string> {
  if (csrfToken) return csrfToken;
  const res = await fetch(`${API_BASE}/csrf`, { credentials: "include" });
  if (!res.ok) throw new Error(`csrf fetch failed: ${res.status}`);
  const body = (await res.json()) as { token: string };
  csrfToken = body.token;
  return body.token;
}

export class BackendUnreachable extends Error {
  constructor() {
    super("backend unreachable");
  }
}

export async function apiFetch<T>(
  path: string,
  init: RequestInit = {},
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
    } catch {
      // swallow — mock mode may not implement csrf
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

  if (res.status === 401) {
    if (
      typeof window !== "undefined" &&
      MODE === "live" &&
      !window.location.pathname.startsWith("/login")
    ) {
      window.location.href = `${API_BASE.replace(/\/api$/, "")}/api/oauth2/authorization/discord`;
    }
    throw new Error("unauthorized");
  }
  if (res.status === 429) {
    const retry = Number(res.headers.get("Retry-After") ?? "1") * 1000;
    await new Promise((r) => setTimeout(r, retry));
    return apiFetch<T>(path, init);
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
