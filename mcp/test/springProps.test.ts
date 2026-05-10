import { describe, it, expect } from "vitest";
import { springPropertyResolve, springPropertyList } from "../src/springProps.js";

describe("spring_property", () => {
  it("resolves a known property under local profile", async () => {
    const out = await springPropertyResolve({
      key: "dev.tylercash.cors.allowed-origins",
      profiles: "local,nonprod",
    });
    expect(out).toMatch(/profiles: \[local, nonprod\]/);
    expect(out).toMatch(/merge order:/);
    // Should produce a value or a not-found note (not crash).
    expect(out).not.toMatch(/^Error/);
  });

  it("lists properties under a prefix", async () => {
    const out = await springPropertyList({
      profiles: "local",
      prefix: "spring.session",
    });
    expect(out).toMatch(/prefix='spring.session'/);
  });
});
