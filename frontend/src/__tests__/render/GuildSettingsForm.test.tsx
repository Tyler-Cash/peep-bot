// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, trapConsoleError } from "../setup/render";
import { GuildSettingsForm } from "@/components/guild/GuildSettingsForm";

vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "mockguild-1" }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/guild/mockguild-1/settings",
}));

// MSW handlers in src/mocks/handlers.ts service the SWR fetches.

describe("GuildSettingsForm (render harness)", () => {
  it("mounts without crashing and renders the settings cards", async () => {
    const { errors, restore } = trapConsoleError();
    try {
      renderWithProviders(<GuildSettingsForm guildId="mockguild-1" />);

      // Once settings + roles + categories load via MSW, the cards render their headings.
      await screen.findByRole("heading", { name: /rsvp emoji/i }, { timeout: 3000 });
      await screen.findByRole("heading", { name: /categories & archive/i });
      await screen.findByRole("heading", { name: /primary location/i });
      await screen.findByRole("heading", { name: /roles & permissions/i });
    } finally {
      restore();
    }

    expect(errors).toEqual([]);
  });
});
