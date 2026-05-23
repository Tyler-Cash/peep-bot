import { vi, describe, it, expect, beforeEach } from "vitest";

vi.mock("next/headers", () => ({
  cookies: vi.fn(),
}));

import { GET } from "@/app/api/avatar/[snowflake]/route";
import { cookies } from "next/headers";

const VALID_SNOWFLAKE = "123456789012345678";
const URL_FOR = (s: string) => `http://x/api/avatar/${s}`;
const PARAMS = (snowflake: string) => ({
  params: Promise.resolve({ snowflake }),
});

function mockSession(value: string | undefined) {
  (cookies as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
    get: () => (value ? { value } : undefined),
  });
}

describe("GET /api/avatar/[snowflake]", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("returns 401 when the SESSION cookie is missing", async () => {
    mockSession(undefined);
    const res = await GET(new Request(URL_FOR(VALID_SNOWFLAKE)), PARAMS(VALID_SNOWFLAKE));
    expect(res.status).toBe(401);
  });

  it("returns 400 for a malformed snowflake — never proxies upstream", async () => {
    mockSession("s");
    const fetchSpy = vi.spyOn(global, "fetch");
    const res = await GET(new Request(URL_FOR("not-a-snowflake")), PARAMS("not-a-snowflake"));
    expect(res.status).toBe(400);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("sets cache-control to private — never public", async () => {
    mockSession("s");
    vi.spyOn(global, "fetch").mockResolvedValueOnce(
      new Response(new Uint8Array([1, 2, 3]), {
        status: 200,
        headers: { "content-type": "image/webp" },
      }),
    );
    const res = await GET(new Request(URL_FOR(VALID_SNOWFLAKE)), PARAMS(VALID_SNOWFLAKE));
    expect(res.status).toBe(200);
    const cc = res.headers.get("cache-control") ?? "";
    expect(cc).toMatch(/private/);
    expect(cc).not.toMatch(/public/);
  });

  it("forwards upstream Etag and Last-Modified back to the client", async () => {
    mockSession("s");
    vi.spyOn(global, "fetch").mockResolvedValueOnce(
      new Response(new Uint8Array([1]), {
        status: 200,
        headers: {
          "content-type": "image/webp",
          etag: '"abc"',
          "last-modified": "Mon, 23 May 2026 00:00:00 GMT",
        },
      }),
    );
    const res = await GET(new Request(URL_FOR(VALID_SNOWFLAKE)), PARAMS(VALID_SNOWFLAKE));
    expect(res.headers.get("etag")).toBe('"abc"');
    expect(res.headers.get("last-modified")).toBe("Mon, 23 May 2026 00:00:00 GMT");
  });

  it("forwards If-None-Match to upstream and passes a 304 back with no body", async () => {
    mockSession("s");
    const fetchSpy = vi.spyOn(global, "fetch").mockResolvedValueOnce(
      new Response(null, { status: 304, headers: { etag: '"abc"' } }),
    );
    const res = await GET(
      new Request(URL_FOR(VALID_SNOWFLAKE), {
        headers: { "if-none-match": '"abc"' },
      }),
      PARAMS(VALID_SNOWFLAKE),
    );
    expect(res.status).toBe(304);
    expect(res.body).toBeNull();
    const sentHeaders = (fetchSpy.mock.calls[0][1] as RequestInit).headers as Record<string, string>;
    expect(sentHeaders["if-none-match"]).toBe('"abc"');
    expect(sentHeaders.cookie).toBe("SESSION=s");
  });
});
