import { vi, describe, it, expect, beforeEach, afterEach } from "vitest";
import { POST } from "@/app/api/traces/route";
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
