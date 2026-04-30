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

describe("EventsFeed render — empty state", () => {
  it("renders 0 upcoming without crashing when /event returns an empty page", async () => {
    server.use(
      http.get(/\/event$/, () =>
        HttpResponse.json({ content: [], totalElements: 0 }),
      ),
    );
    const { errors, restore } = trapConsoleError();
    try {
      renderWithProviders(<EventsFeed />);
      // EventsFeed shows the count badge — "0 upcoming" when no events
      await screen.findByText(/upcoming/i, undefined, { timeout: 3000 });
      // The count badge should show "0"
      const badge = screen.getByText("0");
      expect(badge).toBeTruthy();
    } finally {
      restore();
    }
    expect(errors).toEqual([]);
  });
});
