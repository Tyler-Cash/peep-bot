import { describe, it, expect } from "vitest";
import {
  groupEvents,
  joinNames,
  relTime,
  type ActivityEvent,
} from "@/components/event/ActivityLog";

const NOW = new Date("2026-05-04T12:00:00Z");

function ev(
  id: string,
  who: string,
  kind: ActivityEvent["kind"],
  hoursAgo: number,
): ActivityEvent {
  return {
    id,
    who,
    avatarUrl: null,
    kind,
    at: new Date(NOW.getTime() - hoursAgo * 3600_000).toISOString(),
  };
}

describe("relTime", () => {
  it("renders 0h as 'just now'", () => expect(relTime(0)).toBe("just now"));
  it("renders <24h as Nh ago", () => expect(relTime(8)).toBe("8h ago"));
  it("renders >=24h as Nd ago", () => expect(relTime(48)).toBe("2d ago"));
});

describe("joinNames", () => {
  it("1 name", () => expect(joinNames(["A"])).toBe("A"));
  it("2 names", () => expect(joinNames(["A", "B"])).toBe("A & B"));
  it("3 names", () => expect(joinNames(["A", "B", "C"])).toBe("A, B & C"));
  it("4+ names", () =>
    expect(joinNames(["A", "B", "C", "D"])).toBe("A, B & 2 others"));
});

describe("groupEvents", () => {
  it("merges three same-hour same-kind 'going' RSVPs into one row", () => {
    const events = [
      ev("1", "Kim", "going", 22),
      ev("2", "Koa", "going", 22),
      ev("3", "Suki", "going", 22),
    ];
    const groups = groupEvents(events, NOW);
    expect(groups).toHaveLength(1);
    expect(groups[0].people.map((p) => p.who)).toEqual(["Kim", "Koa", "Suki"]);
    expect(groups[0].kind).toBe("going");
  });

  it("does not merge different kinds in the same hour", () => {
    const events = [
      ev("1", "Wren", "declined", 8),
      ev("2", "Bas", "maybe", 8),
    ];
    const groups = groupEvents(events, NOW);
    expect(groups).toHaveLength(2);
  });

  it("never merges the host row even when same hour as a 'going' rsvp", () => {
    const events = [
      ev("h", "Mira", "host", 8),
      ev("1", "Otis", "going", 8),
    ];
    const groups = groupEvents(events, NOW);
    expect(groups).toHaveLength(2);
  });

  it("sorts newest-first", () => {
    const groups = groupEvents(
      [ev("old", "A", "going", 48), ev("new", "B", "going", 1)],
      NOW,
    );
    expect(groups[0].people[0].who).toBe("B");
    expect(groups[1].people[0].who).toBe("A");
  });
});
