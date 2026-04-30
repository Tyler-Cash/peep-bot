// @vitest-environment jsdom
import { beforeAll, describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { screen } from "@testing-library/react";
import { server } from "@/mocks/server";
import { renderWithProviders, trapConsoleError } from "../../setup/render";
import { Rewind } from "@/components/rewind/Rewind";

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

vi.mock("next/navigation", () => ({
  useParams: () => ({}),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/rewind",
}));

describe("Rewind render — empty years list", () => {
  it("renders without crashing when /rewind/years returns []", async () => {
    server.use(
      http.get(/\/rewind\/years$/, () => HttpResponse.json([])),
    );
    const { errors, restore } = trapConsoleError();
    try {
      renderWithProviders(<Rewind />);
      // Rewind.tsx line 23: availableYears = years ?? [currentYear]
      // When years resolves to [], availableYears = [] so no year buttons are shown.
      // The hero section still renders with the current year in the h1.
      // There is no dedicated empty-state message — the component renders the current year
      // from useState(currentYear) in the h1 and shows "loading rewind…" until rewind data loads.
      await screen.findByText(/peepbot rewind/i, undefined, { timeout: 3000 });
    } finally {
      restore();
    }
    expect(errors).toEqual([]);
  });
});
