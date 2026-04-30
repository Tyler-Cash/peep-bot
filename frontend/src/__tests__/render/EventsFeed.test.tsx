// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, trapConsoleError } from "../setup/render";
import { EventsFeed } from "@/components/feed/EventsFeed";

vi.mock("next/navigation", () => ({
  useParams: () => ({}),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/",
}));

// MSW handlers in src/mocks/handlers.ts service the SWR fetches.

describe("EventsFeed (render harness)", () => {
  it("mounts without crashing and shows a fixture event", async () => {
    const { errors, restore } = trapConsoleError();
    try {
      renderWithProviders(<EventsFeed />);

      // "pub quiz at the glass barrel" is store.events[0].name in fixtures.ts
      await screen.findByText(/pub quiz at the glass barrel/i, undefined, { timeout: 3000 });
    } finally {
      restore();
    }

    expect(errors).toEqual([]);
  });
});
