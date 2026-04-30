// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { screen } from "@testing-library/react";
import { server } from "@/mocks/server";
import { renderWithProviders, trapConsoleError } from "../../setup/render";
import { EventsFeed } from "@/components/feed/EventsFeed";

vi.mock("next/navigation", () => ({
  useParams: () => ({}),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/",
}));

describe("EventsFeed render — server error", () => {
  it("renders an error state instead of crashing when /event returns 500", async () => {
    server.use(
      http.get(/\/event$/, () => new HttpResponse(null, { status: 500 })),
    );
    const { errors, restore } = trapConsoleError();
    try {
      renderWithProviders(<EventsFeed />);
      // EventsFeed.tsx line 56-59: error && !events.length renders "can't reach the backend."
      await screen.findByText(/can't reach the backend/i, undefined, {
        timeout: 3000,
      });
    } finally {
      restore();
    }
    // SWR will log the fetch error — allow some console noise, just not React warnings
    expect(errors.length).toBeLessThan(5);
  });
});
