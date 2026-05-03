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
  it("mounts without crashing and shows the primary location label", async () => {
    const { errors, restore } = trapConsoleError();
    try {
      renderWithProviders(<GuildSettingsForm guildId="mockguild-1" />);

      // The default active tab ("Roles & channels") shows the events role field once settings load.
      await screen.findByText(/events role/i, undefined, { timeout: 3000 });
    } finally {
      restore();
    }

    expect(errors).toEqual([]);
  });
});
