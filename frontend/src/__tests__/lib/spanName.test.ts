import { describe, it, expect } from "vitest";
import { templatePath, fetchSpanName } from "@/lib/otel/spanName";

describe("templatePath", () => {
  it("collapses UUID segments", () => {
    expect(templatePath("/api/event/cb02b96e-c3dc-4d7f-8be4-c56ff2fd498c")).toBe(
      "/api/event/{id}",
    );
    expect(
      templatePath("/api/gallery/thumbnail/6c97616b-5e38-4a1e-803c-08bf0a13b253"),
    ).toBe("/api/gallery/thumbnail/{id}");
  });

  it("collapses snowflake-style numeric ids", () => {
    expect(templatePath("/api/avatar/123456789012345678")).toBe("/api/avatar/{id}");
  });

  it("leaves static paths untouched", () => {
    expect(templatePath("/api/csrf")).toBe("/api/csrf");
    expect(templatePath("/api/auth/is-logged-in")).toBe("/api/auth/is-logged-in");
  });
});

describe("fetchSpanName", () => {
  it("builds METHOD + templated path and uppercases the method", () => {
    expect(fetchSpanName("get", "/api/event/cb02b96e-c3dc-4d7f-8be4-c56ff2fd498c")).toBe(
      "GET /api/event/{id}",
    );
  });

  it("defaults the method to GET when undefined", () => {
    expect(fetchSpanName(undefined, "/api/csrf")).toBe("GET /api/csrf");
  });
});
