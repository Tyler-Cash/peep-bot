// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { screen } from "@testing-library/react";
import { server } from "@/mocks/server";
import { renderWithProviders, trapConsoleError } from "../../setup/render";
import { GuildSettingsForm } from "@/components/guild/GuildSettingsForm";

vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "mockguild-1" }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/guild/mockguild-1/settings",
}));

describe("GuildSettingsForm render — 403 unauthorized", () => {
  it("renders an 'access denied' error panel when /guild/:id/settings returns 403", async () => {
    // Note: apiFetch redirects to /login in live mode on 401/403, but in mock mode
    // (NEXT_PUBLIC_API_MODE=mock) it throws UnauthorizedError without the redirect.
    // The component now reads error from useGuildSettings() and renders an error panel.
    server.use(
      http.get(/\/guild\/[^/]+\/settings$/, () =>
        new HttpResponse(null, { status: 403 }),
      ),
    );
    const { errors, restore } = trapConsoleError();
    try {
      renderWithProviders(<GuildSettingsForm guildId="mockguild-1" />);
      await screen.findByText(/access denied/i, undefined, { timeout: 3000 });
      expect(screen.queryByText(/^loading…$/i)).toBeNull();
    } finally {
      restore();
    }
    // Allow console noise from the fetch error / UnauthorizedError
    expect(errors.length).toBeLessThan(5);
  });
});
