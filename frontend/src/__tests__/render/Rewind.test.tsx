// @vitest-environment jsdom
import { beforeAll, describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, trapConsoleError } from "../setup/render";
import { Rewind } from "@/components/rewind/Rewind";

vi.mock("next/navigation", () => ({
  useParams: () => ({}),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/rewind",
}));

// jsdom doesn't implement IntersectionObserver — stub it so SocialGraph.tsx
// doesn't throw when it wires up its scroll-in-view animation.
beforeAll(() => {
  if (typeof globalThis.IntersectionObserver === "undefined") {
    globalThis.IntersectionObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof IntersectionObserver;
  }
});

// MSW handlers in src/mocks/handlers.ts service the SWR fetches.

describe("Rewind (render harness)", () => {
  it("mounts without crashing and shows rewind stats", async () => {
    const { errors, restore } = trapConsoleError();
    try {
      renderWithProviders(<Rewind />);

      // The Rewind hero always shows "PEEPBOT REWIND" header text
      await screen.findByText(/PEEPBOT REWIND/i, undefined, { timeout: 3000 });
    } finally {
      restore();
    }

    expect(errors).toEqual([]);
  });
});
