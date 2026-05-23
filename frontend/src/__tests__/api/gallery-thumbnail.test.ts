import { vi, describe, it, expect, beforeEach } from "vitest";

vi.mock("next/headers", () => ({
  cookies: vi.fn(),
}));

import { GET } from "@/app/api/gallery/thumbnail/[albumId]/route";
import { cookies } from "next/headers";

const ALBUM_ID = "abc-123";
const URL_FOR = (id: string) => `http://x/api/gallery/thumbnail/${id}`;
const PARAMS = (albumId: string) => ({
  params: Promise.resolve({ albumId }),
});

function mockSession(value: string | undefined) {
  (cookies as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
    get: () => (value ? { value } : undefined),
  });
}

describe("GET /api/gallery/thumbnail/[albumId]", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("returns 401 when the SESSION cookie is missing", async () => {
    mockSession(undefined);
    const res = await GET(new Request(URL_FOR(ALBUM_ID)), PARAMS(ALBUM_ID));
    expect(res.status).toBe(401);
  });

  it("sets cache-control to private — never public", async () => {
    mockSession("s");
    vi.spyOn(global, "fetch").mockResolvedValueOnce(
      new Response(new Uint8Array([1, 2, 3]), {
        status: 200,
        headers: { "content-type": "image/jpeg" },
      }),
    );
    const res = await GET(new Request(URL_FOR(ALBUM_ID)), PARAMS(ALBUM_ID));
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
          "content-type": "image/jpeg",
          etag: '"thumb-abc"',
          "last-modified": "Mon, 23 May 2026 00:00:00 GMT",
        },
      }),
    );
    const res = await GET(new Request(URL_FOR(ALBUM_ID)), PARAMS(ALBUM_ID));
    expect(res.headers.get("etag")).toBe('"thumb-abc"');
    expect(res.headers.get("last-modified")).toBe("Mon, 23 May 2026 00:00:00 GMT");
  });

  it("forwards If-None-Match to upstream and passes a 304 back with no body", async () => {
    mockSession("s");
    const fetchSpy = vi.spyOn(global, "fetch").mockResolvedValueOnce(
      new Response(null, { status: 304, headers: { etag: '"thumb-abc"' } }),
    );
    const res = await GET(
      new Request(URL_FOR(ALBUM_ID), {
        headers: { "if-none-match": '"thumb-abc"' },
      }),
      PARAMS(ALBUM_ID),
    );
    expect(res.status).toBe(304);
    expect(res.body).toBeNull();
    const sentHeaders = (fetchSpy.mock.calls[0][1] as RequestInit).headers as Record<string, string>;
    expect(sentHeaders["if-none-match"]).toBe('"thumb-abc"');
    expect(sentHeaders.cookie).toBe("SESSION=s");
  });
});
