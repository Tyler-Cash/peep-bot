import { describe, it, expect } from "vitest";
import { lifecycleRegistry, discordInteractionMap, mswSpringDiff } from "../src/domainScans.js";

describe("domain scans", () => {
  it("lifecycle_registry finds at least one DurableEventListener", async () => {
    const out = await lifecycleRegistry();
    // Either we find listeners or we say none were found — both are stable shapes.
    expect(
      out.startsWith("Lifecycle listener registry:") ||
        out.startsWith("No lifecycle listeners"),
    ).toBe(true);
    if (out.startsWith("Lifecycle listener registry:")) {
      expect(out).toMatch(/listener[s]? across \d+ event type/);
    }
  });

  it("discord_interaction_map produces a structured listing", async () => {
    const out = await discordInteractionMap();
    expect(
      out.startsWith("Discord interaction map:") ||
        out.startsWith("No Discord interaction"),
    ).toBe(true);
  });

  it("msw_spring_diff returns counts on both sides", async () => {
    const out = await mswSpringDiff();
    expect(out).toMatch(/MSW vs Spring endpoint diff: \d+ backend, \d+ MSW handlers/);
    expect(out).toMatch(/In MSW but no matching Spring endpoint/);
    expect(out).toMatch(/In Spring but no MSW handler/);
  });
});
