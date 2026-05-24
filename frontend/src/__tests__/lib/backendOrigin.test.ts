import { describe, it, expect } from "vitest";
import { backendOriginRegex } from "@/lib/otel/backendOrigin";

describe("backendOriginRegex", () => {
  it("matches the backend origin (prefix) for an absolute API base", () => {
    const re = backendOriginRegex("https://api.event.tylercash.dev/api");
    expect(re).not.toBeNull();
    expect(re!.test("https://api.event.tylercash.dev/api/event/123")).toBe(true);
    expect(re!.test("https://api.event.tylercash.dev/api/gallery/thumbnail/x")).toBe(true);
  });

  it("does not match unrelated origins (no traceparent leak to third parties)", () => {
    const re = backendOriginRegex("https://api.event.tylercash.dev/api");
    expect(re!.test("https://photos.tylercash.dev/api/assets/x")).toBe(false);
    expect(re!.test("https://places.googleapis.com/v1/places")).toBe(false);
  });

  it("returns null for a relative base (same-origin, already propagated)", () => {
    expect(backendOriginRegex("/api")).toBeNull();
  });

  it("returns null when the base is unset", () => {
    expect(backendOriginRegex(undefined)).toBeNull();
  });
});
