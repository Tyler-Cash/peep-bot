import { describe, it, expect } from "vitest";
import { testAffinityReport, testMocksReport } from "../src/testInfra.js";

describe("test infra reports", () => {
  it("test_affinity groups Spring test classes by approximate cache key", async () => {
    const out = await testAffinityReport({});
    expect(out).toMatch(/Spring test affinity \(approximate context-cache groups\)/);
    expect(out).toMatch(/group [0-9a-f]{12}/);
  });

  it("test_mocks reports topology", async () => {
    const out = await testMocksReport({});
    expect(out).toMatch(/@MockBean \/ @MockitoBean topology/);
  });
});
