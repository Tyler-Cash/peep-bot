// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, trapConsoleError } from "../setup/render";
import { GalleryFeed } from "@/components/gallery/GalleryFeed";

vi.mock("next/navigation", () => ({
  useParams: () => ({}),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/gallery",
}));

// MSW handlers in src/mocks/handlers.ts service the SWR fetches.

describe("GalleryFeed (render harness)", () => {
  it("mounts without crashing and shows a fixture album name", async () => {
    const { errors, restore } = trapConsoleError();
    try {
      renderWithProviders(<GalleryFeed />);

      // galleryAlbums[0].eventName mirrors store.events[0].name = "pub quiz at the glass barrel"
      await screen.findByText(/pub quiz at the glass barrel/i, undefined, { timeout: 3000 });
    } finally {
      restore();
    }

    expect(errors).toEqual([]);
  });
});
