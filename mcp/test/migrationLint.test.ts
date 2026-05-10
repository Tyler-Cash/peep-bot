import { describe, it, expect } from "vitest";
import { migrationLint } from "../src/migrationLint.js";

describe("migration_lint", () => {
  it("scans the live changelog and produces structured output", async () => {
    const out = await migrationLint();
    expect(out).toMatch(/Liquibase migration lint:/);
    expect(out).toMatch(/error=\d+ warning=\d+ info=\d+/);
  });
});
