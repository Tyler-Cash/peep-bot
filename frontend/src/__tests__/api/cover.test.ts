import { vi, describe, it, expect, beforeEach } from "vitest";
import { GET } from "@/app/api/events/[id]/cover/route";

const EVENT_ID = "11111111-2222-3333-4444-555555555555";
const URL_FOR = (id: string) => `http://x/api/events/${id}/cover`;
const PARAMS = (id: string) => ({ params: Promise.resolve({ id }) });

// Cover is intentionally cookie-less + public — it serves the Discord /
// Slack / etc. link-unfurl scraper, which has no SESSION cookie. The backend
// still auth-gates direct fetches; this BFF surface is the public preview
// path. These tests pin that contract so a future "tighten auth" refactor
// can't silently break embed previews.
describe("GET /api/events/[id]/cover", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("returns the image WITHOUT requiring a SESSION cookie", async () => {
    vi.spyOn(global, "fetch").mockResolvedValueOnce(
      new Response(new Uint8Array([1, 2, 3]), {
        status: 200,
        headers: { "content-type": "image/jpeg" },
      }),
    );
    const res = await GET(new Request(URL_FOR(EVENT_ID)), PARAMS(EVENT_ID));
    expect(res.status).toBe(200);
  });

  it("sets cache-control to public — Discord scraper relies on this", async () => {
    vi.spyOn(global, "fetch").mockResolvedValueOnce(
      new Response(new Uint8Array([1]), {
        status: 200,
        headers: { "content-type": "image/jpeg" },
      }),
    );
    const res = await GET(new Request(URL_FOR(EVENT_ID)), PARAMS(EVENT_ID));
    expect(res.headers.get("cache-control")).toMatch(/public/);
  });

  it("does not send a Cookie header to upstream", async () => {
    const fetchSpy = vi.spyOn(global, "fetch").mockResolvedValueOnce(
      new Response(new Uint8Array([1]), {
        status: 200,
        headers: { "content-type": "image/jpeg" },
      }),
    );
    await GET(new Request(URL_FOR(EVENT_ID)), PARAMS(EVENT_ID));
    const sentHeaders = (fetchSpy.mock.calls[0][1] as RequestInit | undefined)?.headers as
      | Record<string, string>
      | undefined;
    expect(sentHeaders?.cookie).toBeUndefined();
    expect(sentHeaders?.Cookie).toBeUndefined();
  });

  it("forwards upstream Etag and Last-Modified back to the client", async () => {
    vi.spyOn(global, "fetch").mockResolvedValueOnce(
      new Response(new Uint8Array([1]), {
        status: 200,
        headers: {
          "content-type": "image/jpeg",
          etag: '"cover-abc"',
          "last-modified": "Mon, 23 May 2026 00:00:00 GMT",
        },
      }),
    );
    const res = await GET(new Request(URL_FOR(EVENT_ID)), PARAMS(EVENT_ID));
    expect(res.headers.get("etag")).toBe('"cover-abc"');
    expect(res.headers.get("last-modified")).toBe("Mon, 23 May 2026 00:00:00 GMT");
  });

  it("forwards If-None-Match to upstream and passes a 304 back with no body", async () => {
    const fetchSpy = vi.spyOn(global, "fetch").mockResolvedValueOnce(
      new Response(null, { status: 304, headers: { etag: '"cover-abc"' } }),
    );
    const res = await GET(
      new Request(URL_FOR(EVENT_ID), {
        headers: { "if-none-match": '"cover-abc"' },
      }),
      PARAMS(EVENT_ID),
    );
    expect(res.status).toBe(304);
    expect(res.body).toBeNull();
    const sentHeaders = (fetchSpy.mock.calls[0][1] as RequestInit).headers as Record<string, string>;
    expect(sentHeaders["if-none-match"]).toBe('"cover-abc"');
  });
});
