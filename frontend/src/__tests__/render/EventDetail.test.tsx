// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, trapConsoleError } from "../setup/render";
import { EventDetail } from "@/components/event/EventDetail";

vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "1" }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/events/1",
}));

// MSW handlers in src/mocks/handlers.ts service the SWR fetches.

describe("EventDetail (render harness)", () => {
  it("mounts without crashing and shows the fixture event title", async () => {
    const { errors, restore } = trapConsoleError();
    try {
      renderWithProviders(<EventDetail id="1" />);

      // store.events[0].name is "pub quiz at the glass barrel"
      await screen.findByText(/pub quiz at the glass barrel/i, undefined, { timeout: 3000 });
    } finally {
      restore();
    }

    expect(errors).toEqual([]);
  });
});
