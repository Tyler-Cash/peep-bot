import { describe, expect, it } from "vitest";
import { FEATURES } from "@/components/admin/TogglesSection";

describe("TogglesSection feature catalog", () => {
  it("includes tfnsw toggle", () => {
    expect(FEATURES.some((f) => f.key === "tfnswEnabled")).toBe(true);
  });
});
