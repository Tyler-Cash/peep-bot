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
  useGuilds: () => ({ data: [{ id: "g1", name: "My Guild" }] }),
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
  if (typeof window !== "undefined") {
    window.history.replaceState(null, "", window.location.pathname);
  }
});

afterEach(() => {
  cleanup();
});

describe("GuildSettingsForm", () => {
  it("redirects non-admin users away from the page", async () => {
    mockUseCurrentUser.mockReturnValue({
      data: { ownedGuildIds: [], username: "joe" },
    });
    mockUseGuildSettings.mockReturnValue({
      data: {
        primaryLocationName: null,
        primaryLocationPlaceId: null,
        primaryLocationLat: null,
        primaryLocationLng: null,
        eventsRole: "events",
        organiserRole: "event-organiser",
        separatorChannel: null,
        emojiAccepted: "✅",
        emojiDeclined: "❌",
        emojiMaybe: "❓",
      },
      isLoading: false,
    });

    render(<GuildSettingsForm guildId="g1" />);

    await waitFor(() => expect(mockPush).toHaveBeenCalledWith("/"));
  });

  it("submits a changed primary location and navigates home", async () => {
    mockUseCurrentUser.mockReturnValue({ data: { ownedGuildIds: ["g1"] } });
    mockUseGuildSettings.mockReturnValue({
      data: {
        primaryLocationName: "Old Town",
        primaryLocationPlaceId: "old-pid",
        primaryLocationLat: 1,
        primaryLocationLng: 2,
        eventsRole: "events",
        organiserRole: "event-organiser",
        separatorChannel: null,
        emojiAccepted: "✅",
        emojiDeclined: "❌",
        emojiMaybe: "❓",
        eventCreateRateLimitPerHour: null,
        defaultEventCreateRateLimitPerHour: 5,
      },
      isLoading: false,
    });
    mockUpdateGuildSettings.mockResolvedValueOnce(undefined);

    const user = userEvent.setup();
    render(<GuildSettingsForm guildId="g1" />);

    // Primary location lives in the "Defaults & limits" tab now.
    await user.click(screen.getByRole("tab", { name: /defaults & limits/i }));
    const loc = screen.getByTestId("location") as HTMLInputElement;
    await waitFor(() => expect(loc.value).toBe("Old Town"));
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
      eventsRole: "events",
      organiserRole: "event-organiser",
      separatorChannel: null,
      emojiAccepted: "✅",
      emojiDeclined: "❌",
      emojiMaybe: "❓",
      eventCreateRateLimitPerHour: null,
    });
    await waitFor(() => expect(mockPush).toHaveBeenCalledWith("/"));
  });

  it("preserves edits when switching tabs", async () => {
    mockUseCurrentUser.mockReturnValue({ data: { ownedGuildIds: ["g1"] } });
    mockUseGuildSettings.mockReturnValue({
      data: {
        primaryLocationName: null,
        primaryLocationPlaceId: null,
        primaryLocationLat: null,
        primaryLocationLng: null,
        eventsRole: "events",
        organiserRole: "event-organiser",
        separatorChannel: null,
        emojiAccepted: "✅",
        emojiDeclined: "❌",
        emojiMaybe: "❓",
        eventCreateRateLimitPerHour: null,
        defaultEventCreateRateLimitPerHour: 5,
      },
      isLoading: false,
    });

    const user = userEvent.setup();
    render(<GuildSettingsForm guildId="g1" />);

    const eventsRoleInput = (await screen.findByDisplayValue("events")) as HTMLInputElement;
    await user.clear(eventsRoleInput);
    await user.type(eventsRoleInput, "events-edit");

    await user.click(screen.getByRole("tab", { name: /rsvp emoji/i }));
    expect(screen.queryByDisplayValue("events-edit")).toBeNull();

    await user.click(screen.getByRole("tab", { name: /roles & channels/i }));
    expect(screen.getByDisplayValue("events-edit")).toBeTruthy();
  });

  it("rate-limit field defaults to 'use server default' (null) and toggles to override", async () => {
    mockUseCurrentUser.mockReturnValue({ data: { ownedGuildIds: ["g1"] } });
    mockUseGuildSettings.mockReturnValue({
      data: {
        primaryLocationName: null,
        primaryLocationPlaceId: null,
        primaryLocationLat: null,
        primaryLocationLng: null,
        eventsRole: "events",
        organiserRole: "event-organiser",
        separatorChannel: null,
        emojiAccepted: "✅",
        emojiDeclined: "❌",
        emojiMaybe: "❓",
        eventCreateRateLimitPerHour: null,
        defaultEventCreateRateLimitPerHour: 5,
      },
      isLoading: false,
    });
    mockUpdateGuildSettings.mockResolvedValueOnce(undefined);

    const user = userEvent.setup();
    render(<GuildSettingsForm guildId="g1" />);

    await user.click(screen.getByRole("tab", { name: /defaults & limits/i }));
    const checkbox = screen.getByRole("checkbox", { name: /use server default/i });
    expect((checkbox as HTMLInputElement).checked).toBe(true);

    await user.click(checkbox);
    expect((checkbox as HTMLInputElement).checked).toBe(false);
    await user.click(screen.getByRole("button", { name: "7 / hour" }));

    await user.click(screen.getByRole("button", { name: /save settings/i }));
    await waitFor(() =>
      expect(mockUpdateGuildSettings).toHaveBeenCalledTimes(1),
    );
    expect(mockUpdateGuildSettings.mock.calls[0][1].eventCreateRateLimitPerHour).toBe(7);
  });

  it("rate-limit field re-checking 'use default' submits null", async () => {
    mockUseCurrentUser.mockReturnValue({ data: { ownedGuildIds: ["g1"] } });
    mockUseGuildSettings.mockReturnValue({
      data: {
        primaryLocationName: null,
        primaryLocationPlaceId: null,
        primaryLocationLat: null,
        primaryLocationLng: null,
        eventsRole: "events",
        organiserRole: "event-organiser",
        separatorChannel: null,
        emojiAccepted: "✅",
        emojiDeclined: "❌",
        emojiMaybe: "❓",
        eventCreateRateLimitPerHour: 7,
        defaultEventCreateRateLimitPerHour: 5,
      },
      isLoading: false,
    });
    mockUpdateGuildSettings.mockResolvedValueOnce(undefined);

    const user = userEvent.setup();
    render(<GuildSettingsForm guildId="g1" />);

    await user.click(screen.getByRole("tab", { name: /defaults & limits/i }));
    const checkbox = screen.getByRole("checkbox", { name: /use server default/i });
    expect((checkbox as HTMLInputElement).checked).toBe(false);

    await user.click(checkbox);
    expect((checkbox as HTMLInputElement).checked).toBe(true);

    await user.click(screen.getByRole("button", { name: /save settings/i }));
    await waitFor(() =>
      expect(mockUpdateGuildSettings).toHaveBeenCalledTimes(1),
    );
    expect(mockUpdateGuildSettings.mock.calls[0][1].eventCreateRateLimitPerHour).toBeNull();
  });

  it("shows the loading state and does not submit while settings are loading", async () => {
    mockUseCurrentUser.mockReturnValue({ data: { ownedGuildIds: ["g1"] } });
    mockUseGuildSettings.mockReturnValue({ data: undefined, isLoading: true });

    render(<GuildSettingsForm guildId="g1" />);

    expect(screen.getByText(/loading…/)).toBeTruthy();
    expect(
      screen.queryByRole("button", { name: /save settings/i }),
    ).toBeNull();
    expect(mockUpdateGuildSettings).not.toHaveBeenCalled();
  });
});
