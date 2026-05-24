import { vi, describe, it, expect, beforeEach, afterEach } from "vitest";
import { GET, POST } from "@/app/api/traces/route";
import { PUBLIC_OTLP_ENDPOINT } from "@/lib/otel/endpoint";

const ENDPOINT = "http://localhost/api/traces";
const PAYLOAD = JSON.stringify({ resourceSpans: [] });

function spanPost(body: string = PAYLOAD, contentType = "application/json") {
  return new Request(ENDPOINT, {
    method: "POST",
    body,
    headers: { "content-type": contentType },
  });
}

describe("POST /api/traces (browser-span collector proxy)", () => {
  beforeEach(() => {
    delete process.env.OTEL_EXPORTER_OTLP_ENDPOINT;
    delete process.env.OTEL_EXPORTER_OTLP_HEADERS;
  });
  afterEach(() => vi.restoreAllMocks());

  it("forwards the body to <endpoint>/v1/traces and returns 204 on success", async () => {
    const spy = vi
      .spyOn(global, "fetch")
      .mockResolvedValueOnce(new Response(null, { status: 200 }));
    const res = await POST(spanPost());
    expect(res.status).toBe(204);
    expect(res.headers.get("cache-control")).toBe("no-store");
    expect(spy).toHaveBeenCalledOnce();
    const [url, init] = spy.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(`${PUBLIC_OTLP_ENDPOINT}/v1/traces`);
    expect(init.method).toBe("POST");
  });

  it("attaches the secret auth header (Authorization value split on first '=')", async () => {
    process.env.OTEL_EXPORTER_OTLP_HEADERS = "Authorization=Basic dXNlcjpwYXNz==";
    const spy = vi
      .spyOn(global, "fetch")
      .mockResolvedValueOnce(new Response(null, { status: 200 }));
    await POST(spanPost());
    const [, init] = spy.mock.calls[0] as [string, RequestInit];
    expect((init.headers as Record<string, string>)["Authorization"]).toBe(
      "Basic dXNlcjpwYXNz==",
    );
  });

  it("honors an internal collector endpoint override", async () => {
    process.env.OTEL_EXPORTER_OTLP_ENDPOINT = "http://grafana-lgtm:4318";
    const spy = vi
      .spyOn(global, "fetch")
      .mockResolvedValueOnce(new Response(null, { status: 200 }));
    await POST(spanPost());
    const [url] = spy.mock.calls[0] as [string];
    expect(url).toBe("http://grafana-lgtm:4318/v1/traces");
  });

  it("normalizes an endpoint that already includes /v1/traces (no doubled path)", async () => {
    process.env.OTEL_EXPORTER_OTLP_ENDPOINT = "https://otel.tylercash.dev/v1/traces/";
    const spy = vi
      .spyOn(global, "fetch")
      .mockResolvedValueOnce(new Response(null, { status: 200 }));
    await POST(spanPost());
    const [url] = spy.mock.calls[0] as [string];
    expect(url).toBe("https://otel.tylercash.dev/v1/traces");
  });

  it("surfaces a non-2xx upstream status (e.g. 401 auth) instead of a generic 502", async () => {
    vi.spyOn(global, "fetch").mockResolvedValueOnce(
      new Response("unauthorized", { status: 401 }),
    );
    const res = await POST(spanPost());
    expect(res.status).toBe(401);
    expect(res.headers.get("x-otlp-upstream-status")).toBe("401");
  });

  it("returns 502 when the collector is unreachable (never throws into the page)", async () => {
    vi.spyOn(global, "fetch").mockRejectedValueOnce(new Error("down"));
    const res = await POST(spanPost());
    expect(res.status).toBe(502);
  });

  it("short-circuits an empty body without calling upstream", async () => {
    const spy = vi.spyOn(global, "fetch");
    const res = await POST(spanPost(""));
    expect(res.status).toBe(204);
    expect(spy).not.toHaveBeenCalled();
  });
});

describe("GET /api/traces (clock-sync time source)", () => {
  it("returns the server epoch millis as a number, uncached", async () => {
    const before = Date.now();
    const res = await GET();
    const after = Date.now();
    expect(res.headers.get("cache-control")).toBe("no-store");
    const body = (await res.json()) as { now: number };
    expect(typeof body.now).toBe("number");
    expect(body.now).toBeGreaterThanOrEqual(before);
    expect(body.now).toBeLessThanOrEqual(after);
  });
});

describe("POST /api/traces clock-skew correction", () => {
  afterEach(() => vi.restoreAllMocks());

  type Span = {
    startTimeUnixNano: string;
    endTimeUnixNano: string;
    events: { timeUnixNano: string }[];
  };

  function payloadWithOffset(offset?: string): string {
    return JSON.stringify({
      resourceSpans: [
        {
          scopeSpans: [
            {
              spans: [
                {
                  startTimeUnixNano: "1000000000000000000",
                  endTimeUnixNano: "1000000000050000000",
                  attributes:
                    offset === undefined
                      ? []
                      : [{ key: "browser.clock.offset_ms", value: { intValue: offset } }],
                  events: [{ timeUnixNano: "1000000000010000000" }],
                },
              ],
            },
          ],
        },
      ],
    });
  }

  async function forwardedSpan(payload: string): Promise<Span> {
    const spy = vi
      .spyOn(global, "fetch")
      .mockResolvedValueOnce(new Response(null, { status: 200 }));
    await POST(spanPost(payload));
    const [, init] = spy.mock.calls[0] as [string, RequestInit];
    const parsed = JSON.parse(init.body as string) as {
      resourceSpans: { scopeSpans: { spans: Span[] }[] }[];
    };
    return parsed.resourceSpans[0].scopeSpans[0].spans[0];
  }

  it("shifts span start/end and event times by browser.clock.offset_ms", async () => {
    const span = await forwardedSpan(payloadWithOffset("200"));
    expect(span.startTimeUnixNano).toBe("1000000000200000000");
    expect(span.endTimeUnixNano).toBe("1000000000250000000");
    expect(span.events[0].timeUnixNano).toBe("1000000000210000000");
  });

  it("handles a negative offset (browser clock ahead of server)", async () => {
    const span = await forwardedSpan(payloadWithOffset("-150"));
    expect(span.startTimeUnixNano).toBe("999999999850000000");
  });

  it("ignores an implausibly large offset", async () => {
    const span = await forwardedSpan(payloadWithOffset(String(11 * 60 * 1000)));
    expect(span.startTimeUnixNano).toBe("1000000000000000000");
  });

  it("forwards spans without an offset attribute untouched", async () => {
    const span = await forwardedSpan(payloadWithOffset());
    expect(span.startTimeUnixNano).toBe("1000000000000000000");
  });
});
