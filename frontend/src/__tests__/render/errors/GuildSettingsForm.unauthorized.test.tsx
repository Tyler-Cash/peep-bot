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
  it("renders without crashing when /guild/:id/settings returns 403", async () => {
    server.use(
      http.get(/\/guild\/[^/]+\/settings$/, () =>
        new HttpResponse(null, { status: 403 }),
      ),
    );
    const { errors, restore } = trapConsoleError();
    try {
      renderWithProviders(<GuildSettingsForm guildId="mockguild-1" />);
      // TODO: GuildSettingsForm.tsx has NO dedicated unauthorized/error UI state — bug to fix.
      // When useGuildSettings() errors with 403 (UnauthorizedError via apiFetch/fetcher),
      // `settings` stays undefined so the component shows "loading…" forever.
      // There is a non-admin redirect (line 48-50) but it only fires when `user.admin` is false,
      // not when the settings fetch itself is unauthorized.
      // Bug location: frontend/src/components/guild/GuildSettingsForm.tsx line 71-73
      // Fix needed: read `error` from useGuildSettings() and render an access-denied message
      // (or redirect) when it throws UnauthorizedError.
      await screen.findByText(/loading/i, undefined, { timeout: 3000 });
    } finally {
      restore();
    }
    // Allow console noise from the fetch error / UnauthorizedError
    expect(errors.length).toBeLessThan(5);
  });
});
