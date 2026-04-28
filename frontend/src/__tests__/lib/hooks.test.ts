// @vitest-environment jsdom
import { describe, it, expect, beforeEach } from "vitest";
import { getActiveGuildId } from "@/lib/hooks";
import type { Guild } from "@/lib/types";

const guilds = [
  { id: "g1", name: "First", channel: "outings" } as Guild,
  { id: "g2", name: "Second", channel: "outings" } as Guild,
];

describe("getActiveGuildId", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("returns first guild when nothing stored", () => {
    expect(getActiveGuildId(guilds)).toBe("g1");
  });

  it("returns stored guild when it still exists in the list", () => {
    window.localStorage.setItem("peepbot.activeGuild", "g2");
    expect(getActiveGuildId(guilds)).toBe("g2");
  });

  it("falls back to first guild when stored id is no longer present", () => {
    window.localStorage.setItem("peepbot.activeGuild", "deleted");
    expect(getActiveGuildId(guilds)).toBe("g1");
  });

  it("returns null when there are no guilds", () => {
    expect(getActiveGuildId([])).toBeNull();
    expect(getActiveGuildId(undefined)).toBeNull();
  });
});
