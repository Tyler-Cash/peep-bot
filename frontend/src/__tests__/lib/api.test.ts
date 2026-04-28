import { vi, describe, it, expect, beforeEach, afterEach } from "vitest";

// Tests for src/lib/api.ts. Module-level CSRF cache means we use
// vi.resetModules() + dynamic import per test for isolation.

const mockFetch = vi.fn();
global.fetch = mockFetch as unknown as typeof fetch;

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
    ...init,
  });
}

beforeEach(() => {
  vi.resetModules();
  mockFetch.mockReset();
  // Force "live" so 429 retries and CSRF errors aren't silently swallowed.
  process.env.NEXT_PUBLIC_API_MODE = "live";
  process.env.NEXT_PUBLIC_API_BASE = "/api";
});

afterEach(() => {
  delete process.env.NEXT_PUBLIC_API_MODE;
  delete process.env.NEXT_PUBLIC_API_BASE;
  vi.useRealTimers();
});

describe("apiFetch", () => {
  it("does not request CSRF for GET and includes credentials", async () => {
    mockFetch.mockResolvedValueOnce(jsonResponse({ ok: true }));
    const { apiFetch } = await import("@/lib/api");

    const result = await apiFetch<{ ok: boolean }>("/foo");

    expect(result).toEqual({ ok: true });
    expect(mockFetch).toHaveBeenCalledTimes(1);
    const [url, init] = mockFetch.mock.calls[0];
    expect(url).toBe("/api/foo");
    expect(init.method).toBe("GET");
    expect(init.credentials).toBe("include");
    expect(init.headers.get("X-XSRF-TOKEN")).toBeNull();
    expect(init.headers.get("Accept")).toBe("application/json");
  });

  it("fetches CSRF before mutation and sends X-XSRF-TOKEN header", async () => {
    mockFetch
      .mockResolvedValueOnce(jsonResponse({ token: "csrf-abc" })) // /csrf
      .mockResolvedValueOnce(jsonResponse({ id: 1 })); // PUT /foo
    const { apiFetch } = await import("@/lib/api");

    const result = await apiFetch<{ id: number }>("/foo", {
      method: "PUT",
      body: JSON.stringify({ x: 1 }),
    });

    expect(result).toEqual({ id: 1 });
    expect(mockFetch).toHaveBeenCalledTimes(2);
    expect(mockFetch.mock.calls[0][0]).toBe("/api/csrf");
    expect(mockFetch.mock.calls[0][1].credentials).toBe("include");
    const putInit = mockFetch.mock.calls[1][1];
    expect(putInit.method).toBe("PUT");
    expect(putInit.credentials).toBe("include");
    expect(putInit.headers.get("X-XSRF-TOKEN")).toBe("csrf-abc");
    expect(putInit.headers.get("Content-Type")).toBe("application/json");
  });

  it("caches CSRF token across mutations", async () => {
    mockFetch
      .mockResolvedValueOnce(jsonResponse({ token: "csrf-xyz" }))
      .mockResolvedValueOnce(jsonResponse({}))
      .mockResolvedValueOnce(jsonResponse({}));
    const { apiFetch } = await import("@/lib/api");

    await apiFetch("/a", { method: "POST", body: "{}" });
    await apiFetch("/b", { method: "DELETE" });

    // /csrf called once, then POST /a, then DELETE /b
    expect(mockFetch).toHaveBeenCalledTimes(3);
    expect(mockFetch.mock.calls[0][0]).toBe("/api/csrf");
    expect(mockFetch.mock.calls[1][0]).toBe("/api/a");
    expect(mockFetch.mock.calls[2][0]).toBe("/api/b");
    expect(mockFetch.mock.calls[2][1].headers.get("X-XSRF-TOKEN")).toBe(
      "csrf-xyz",
    );
  });

  it("throws UnauthorizedError on 401", async () => {
    mockFetch.mockResolvedValueOnce(new Response(null, { status: 401 }));
    const { apiFetch, UnauthorizedError } = await import("@/lib/api");

    await expect(apiFetch("/me")).rejects.toBeInstanceOf(UnauthorizedError);
  });

  it("throws UnauthorizedError on 403", async () => {
    mockFetch.mockResolvedValueOnce(new Response(null, { status: 403 }));
    const { apiFetch, UnauthorizedError } = await import("@/lib/api");

    await expect(apiFetch("/admin")).rejects.toBeInstanceOf(UnauthorizedError);
  });

  it("retries on 429 honoring Retry-After then succeeds", async () => {
    vi.useFakeTimers();
    mockFetch
      .mockResolvedValueOnce(
        new Response(null, {
          status: 429,
          headers: { "Retry-After": "2" },
        }),
      )
      .mockResolvedValueOnce(jsonResponse({ ok: true }));
    const { apiFetch } = await import("@/lib/api");

    const promise = apiFetch<{ ok: boolean }>("/foo");
    // First call should have happened immediately.
    expect(mockFetch).toHaveBeenCalledTimes(1);
    // Advance past the 2-second Retry-After.
    await vi.advanceTimersByTimeAsync(2000);
    const result = await promise;

    expect(result).toEqual({ ok: true });
    expect(mockFetch).toHaveBeenCalledTimes(2);
  });

  it("gives up after 2 retries on persistent 429", async () => {
    vi.useFakeTimers();
    const tooMany = () =>
      new Response(null, {
        status: 429,
        headers: { "Retry-After": "1" },
      });
    mockFetch
      .mockResolvedValueOnce(tooMany())
      .mockResolvedValueOnce(tooMany())
      .mockResolvedValueOnce(tooMany());
    const { apiFetch } = await import("@/lib/api");

    const promise = apiFetch("/foo").catch((e) => e);
    // Drain both Retry-After waits.
    await vi.advanceTimersByTimeAsync(1000);
    await vi.advanceTimersByTimeAsync(1000);
    const err = await promise;

    expect(err).toBeInstanceOf(Error);
    expect((err as Error).message).toMatch(/rate limited/);
    // Initial call + 2 retries = 3 total.
    expect(mockFetch).toHaveBeenCalledTimes(3);
  });

  it("surfaces status code in error for non-ok responses", async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify({ message: "boom" }), { status: 500 }),
    );
    const { apiFetch } = await import("@/lib/api");

    await expect(apiFetch("/foo")).rejects.toThrow(/500/);
  });

  it("throws BackendUnreachable when fetch itself rejects", async () => {
    mockFetch.mockRejectedValueOnce(new TypeError("network down"));
    const { apiFetch, BackendUnreachable } = await import("@/lib/api");

    await expect(apiFetch("/foo")).rejects.toBeInstanceOf(BackendUnreachable);
  });

  it("returns undefined for 204 No Content", async () => {
    mockFetch
      .mockResolvedValueOnce(jsonResponse({ token: "t" }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const { apiFetch } = await import("@/lib/api");

    const result = await apiFetch("/foo", { method: "DELETE" });
    expect(result).toBeUndefined();
  });

  it("propagates CSRF fetch failure in live mode", async () => {
    mockFetch.mockRejectedValueOnce(new TypeError("network down"));
    const { apiFetch, BackendUnreachable } = await import("@/lib/api");

    await expect(
      apiFetch("/foo", { method: "POST", body: "{}" }),
    ).rejects.toBeInstanceOf(BackendUnreachable);
    // Mutation never attempted because CSRF fetch blew up first.
    expect(mockFetch).toHaveBeenCalledTimes(1);
  });
});

describe("apiFetch in mock mode", () => {
  it("swallows CSRF fetch failures and proceeds with mutation", async () => {
    process.env.NEXT_PUBLIC_API_MODE = "mock";
    mockFetch
      .mockRejectedValueOnce(new TypeError("csrf endpoint missing"))
      .mockResolvedValueOnce(jsonResponse({ ok: true }));
    const { apiFetch } = await import("@/lib/api");

    const result = await apiFetch<{ ok: boolean }>("/foo", {
      method: "POST",
      body: "{}",
    });

    expect(result).toEqual({ ok: true });
    expect(mockFetch).toHaveBeenCalledTimes(2);
    expect(mockFetch.mock.calls[1][1].headers.get("X-XSRF-TOKEN")).toBeNull();
  });
});
