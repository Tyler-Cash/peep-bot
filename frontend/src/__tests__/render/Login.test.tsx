// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, trapConsoleError } from "../setup/render";
import { LoginHero } from "@/components/login/LoginHero";

vi.mock("next/navigation", () => ({
  useParams: () => ({}),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/login",
}));

// MSW handlers in src/mocks/handlers.ts service the SWR fetches.

describe("LoginHero (render harness)", () => {
  it("mounts without crashing and shows the Discord CTA button", async () => {
    const { errors, restore } = trapConsoleError();
    try {
      renderWithProviders(<LoginHero />);

      // LoginHero renders "continue with Discord" as the primary CTA button
      await screen.findByText(/continue with Discord/i, undefined, { timeout: 3000 });
    } finally {
      restore();
    }

    expect(errors).toEqual([]);
  });
});
