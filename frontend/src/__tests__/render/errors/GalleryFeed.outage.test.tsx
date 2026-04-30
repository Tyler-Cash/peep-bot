// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { screen } from "@testing-library/react";
import { server } from "@/mocks/server";
import { renderWithProviders, trapConsoleError } from "../../setup/render";
import { GalleryFeed } from "@/components/gallery/GalleryFeed";

vi.mock("next/navigation", () => ({
  useParams: () => ({}),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/gallery",
}));

describe("GalleryFeed render — Immich outage (502)", () => {
  it("renders an outage message instead of crashing when /gallery returns 502", async () => {
    server.use(
      http.get(/\/gallery$/, () => new HttpResponse(null, { status: 502 })),
    );
    const { errors, restore } = trapConsoleError();
    try {
      renderWithProviders(<GalleryFeed />);
      // GalleryFeed.tsx line 18-25: error branch renders "can't load gallery right now."
      await screen.findByText(/can't load gallery right now/i, undefined, {
        timeout: 3000,
      });
    } finally {
      restore();
    }
    expect(errors.length).toBeLessThan(5);
  });
});
