// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import React from "react";

// --- Mocks -----------------------------------------------------------------
const mockPush = vi.fn();
const mockUpdateGuildSettings = vi.fn();
const mockUseCurrentUser = vi.fn();
const mockUseGuildSettings = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mockPush }),
}));

vi.mock("next/link", () => ({
  default: function Link({
    children,
    href,
  }: {
    children: React.ReactNode;
    href: string;
  }) {
    return React.createElement("a", { href }, children);
  },
}));

vi.mock("@/lib/hooks", () => ({
  updateGuildSettings: (...args: unknown[]) => mockUpdateGuildSettings(...args),
  useActiveGuild: () => ({ id: "g1", name: "My Guild", channel: "outings" }),
  useCurrentUser: () => mockUseCurrentUser(),
  useGuildSettings: (id: string) => mockUseGuildSettings(id),
}));

vi.mock("@/lib/places", () => ({
  fetchPlaceDetails: vi.fn(),
  geocodePlace: vi.fn().mockResolvedValue(null),
  newPlacesSessionToken: () => "tok",
}));

vi.mock("@/components/ui/LocationAutocomplete", () => ({
  LocationAutocomplete: function LocationAutocomplete({
    value,
    onChange,
  }: {
    value: string;
    onChange: (v: string) => void;
  }) {
    return React.createElement("input", {
      "data-testid": "location",
      value,
      onChange: (e: React.ChangeEvent<HTMLInputElement>) =>
        onChange(e.target.value),
    });
  },
}));

import { GuildSettingsForm } from "@/components/guild/GuildSettingsForm";

beforeEach(() => {
  mockPush.mockReset();
  mockUpdateGuildSettings.mockReset();
  mockUseCurrentUser.mockReset();
  mockUseGuildSettings.mockReset();
});

afterEach(() => {
  cleanup();
});

describe("GuildSettingsForm", () => {
  it("redirects non-admin users away from the page", async () => {
    mockUseCurrentUser.mockReturnValue({
      data: { admin: false, username: "joe" },
    });
    mockUseGuildSettings.mockReturnValue({
      data: { primaryLocationName: null },
      isLoading: false,
    });

    render(<GuildSettingsForm guildId="g1" />);

    await waitFor(() => expect(mockPush).toHaveBeenCalledWith("/"));
  });

  it("submits a changed primary location and navigates home", async () => {
    mockUseCurrentUser.mockReturnValue({ data: { admin: true } });
    mockUseGuildSettings.mockReturnValue({
      data: {
        primaryLocationName: "Old Town",
        primaryLocationPlaceId: "old-pid",
        primaryLocationLat: 1,
        primaryLocationLng: 2,
      },
      isLoading: false,
    });
    mockUpdateGuildSettings.mockResolvedValueOnce(undefined);

    const user = userEvent.setup();
    render(<GuildSettingsForm guildId="g1" />);

    const loc = screen.getByTestId("location") as HTMLInputElement;
    await waitFor(() => expect(loc.value).toBe("Old Town"));
    // Editing the location field clears the resolved place id/coords.
    await user.clear(loc);
    await user.type(loc, "New Place");

    await user.click(screen.getByRole("button", { name: /save settings/i }));

    await waitFor(() =>
      expect(mockUpdateGuildSettings).toHaveBeenCalledTimes(1),
    );
    const [guildId, payload] = mockUpdateGuildSettings.mock.calls[0];
    expect(guildId).toBe("g1");
    expect(payload).toEqual({
      primaryLocationName: "New Place",
      primaryLocationPlaceId: null,
      primaryLocationLat: null,
      primaryLocationLng: null,
    });
    await waitFor(() => expect(mockPush).toHaveBeenCalledWith("/"));
  });

  it("shows the loading state and does not submit while settings are loading", async () => {
    mockUseCurrentUser.mockReturnValue({ data: { admin: true } });
    mockUseGuildSettings.mockReturnValue({ data: undefined, isLoading: true });

    render(<GuildSettingsForm guildId="g1" />);

    expect(screen.getByText(/loading…/)).toBeTruthy();
    expect(
      screen.queryByRole("button", { name: /save settings/i }),
    ).toBeNull();
    expect(mockUpdateGuildSettings).not.toHaveBeenCalled();
  });
});
