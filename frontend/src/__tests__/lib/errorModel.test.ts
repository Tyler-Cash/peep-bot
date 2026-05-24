import { describe, expect, it } from "vitest";
import {
  ApiError,
  BackendUnreachable,
  ResponseShapeError,
  UnauthorizedError,
  describeError,
  errorRef,
} from "@/lib/api";

describe("errorRef", () => {
  it("extracts traceId/status/method/path from an ApiError body", () => {
    const e = new ApiError(
      500,
      { error: "boom", traceId: "abc123def456", timestamp: "2026-05-24T14:23:01.000Z" },
      "boom",
      { method: "POST", path: "/event" },
    );
    expect(errorRef(e)).toEqual({
      traceId: "abc123def456",
      status: 500,
      method: "POST",
      path: "/event",
      timestamp: "2026-05-24T14:23:01.000Z",
    });
  });

  it("ignores a sentinel 'unknown' traceId from the backend", () => {
    const e = new ApiError(404, { error: "Not found", traceId: "unknown" }, "Not found", {
      method: "GET",
      path: "/event/1",
    });
    expect(errorRef(e)?.traceId).toBeUndefined();
  });

  it("carries method/path but no traceId/status for BackendUnreachable", () => {
    const e = new BackendUnreachable({ method: "POST", path: "/event" });
    const ref = errorRef(e);
    expect(ref?.method).toBe("POST");
    expect(ref?.path).toBe("/event");
    expect(ref?.status).toBeUndefined();
    expect(ref?.traceId).toBeUndefined();
  });

  it("returns null for unknown errors", () => {
    expect(errorRef(new Error("nope"))).toBeNull();
  });
});

describe("describeError", () => {
  it("gives a connection message for BackendUnreachable", () => {
    const { message, ref } = describeError(new BackendUnreachable({ method: "GET", path: "/x" }));
    expect(message).toMatch(/can't reach the server/i);
    expect(ref?.path).toBe("/x");
  });

  it("passes through the backend message for a 4xx", () => {
    const e = new ApiError(409, { message: "name taken" }, "name taken", {
      method: "POST",
      path: "/event",
    });
    expect(describeError(e).message).toBe("name taken");
  });

  it("hides the raw message for a 5xx", () => {
    const e = new ApiError(500, { error: "stacktrace leak" }, "stacktrace leak");
    expect(describeError(e).message).toMatch(/something went wrong/i);
  });

  it("reports a rate-limit message for 429", () => {
    const e = new ApiError(429, null, "too many");
    expect(describeError(e).message).toMatch(/too many requests/i);
  });

  it("describes a ResponseShapeError as an unexpected response", () => {
    const e = new ResponseShapeError("/guild", [], {});
    const { message, ref } = describeError(e);
    expect(message).toMatch(/unexpected response/i);
    expect(ref?.path).toBe("/guild");
  });

  it("still returns no ref for an UnauthorizedError (callers skip it)", () => {
    // describeError never sees UnauthorizedError in practice, but must not throw.
    expect(describeError(new UnauthorizedError()).ref).toBeNull();
  });
});
