// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { screen } from "@testing-library/react";
import { server } from "@/mocks/server";
import { renderWithProviders, trapConsoleError } from "../../setup/render";
import { EventDetail } from "@/components/event/EventDetail";

vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "999" }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/events/999",
}));

describe("EventDetail render — 404 not found", () => {
  it("renders an 'event not found' error panel when /event/:id returns 404", async () => {
    server.use(
      http.get(/\/event\/999$/, () => new HttpResponse(null, { status: 404 })),
    );
    const { errors, restore } = trapConsoleError();
    try {
      renderWithProviders(<EventDetail id="999" />);
      await screen.findByText(/event not found/i, undefined, { timeout: 3000 });
      expect(screen.queryByText(/^loading…$/i)).toBeNull();
    } finally {
      restore();
    }
    // Allow some console noise from the fetch error
    expect(errors.length).toBeLessThan(5);
  });
});
