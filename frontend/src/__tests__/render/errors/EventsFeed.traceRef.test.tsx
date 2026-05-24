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

describe("EventsFeed render — trace ref on load error", () => {
  it("surfaces the backend traceId so a failed GET is reportable", async () => {
    server.use(
      http.get(/\/event$/, () =>
        HttpResponse.json(
          { error: "boom", traceId: "feedface1234", timestamp: "2026-05-24T14:23:01.000Z" },
          { status: 500 },
        ),
      ),
    );
    const { restore } = trapConsoleError();
    try {
      renderWithProviders(<EventsFeed />);
      await screen.findByText(/can't reach the backend/i, undefined, { timeout: 3000 });
      // The trace ref block shows a truncated traceId for the user to quote.
      expect(await screen.findByText(/ref feedface/)).toBeInTheDocument();
    } finally {
      restore();
    }
  });
});
