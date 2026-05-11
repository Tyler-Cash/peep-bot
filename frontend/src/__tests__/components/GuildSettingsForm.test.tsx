// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor, cleanup, fireEvent } from "@testing-library/react";
import React from "react";

const mockPush = vi.fn();
const mockUpdateGuildSettings = vi.fn();
const mockKickBotFromGuild = vi.fn();
const mockUseCurrentUser = vi.fn();
const mockUseGuildSettings = vi.fn();
const mockUseGuildRoles = vi.fn();
const mockUseGuildCategories = vi.fn();

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
  kickBotFromGuild: (...args: unknown[]) => mockKickBotFromGuild(...args),
  useGuilds: () => ({ data: [{ id: "g1", name: "Porch Pigeons" }] }),
  useCurrentUser: () => mockUseCurrentUser(),
  useGuildSettings: (id: string) => mockUseGuildSettings(id),
  useGuildRoles: (id: string) => mockUseGuildRoles(id),
  useGuildCategories: (id: string) => mockUseGuildCategories(id),
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
      onChange: (e: React.ChangeEvent<HTMLInputElement>) => onChange(e.target.value),
    });
  },
}));

import { GuildSettingsForm } from "@/components/guild/GuildSettingsForm";

const defaultSettings = {
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
  plannedCategoryId: null,
  archivedCategoryId: null,
  archiveDays: 90,
  anyoneCanCreate: true,
};

const defaultRoles = [
  { id: "10", name: "events" },
  { id: "20", name: "event-organiser" },
];

const defaultCategories = [
  { id: "100", name: "Active events" },
  { id: "200", name: "Archive" },
];

beforeEach(() => {
  mockPush.mockReset();
  mockUpdateGuildSettings.mockReset();
  mockKickBotFromGuild.mockReset();
  mockUseCurrentUser.mockReset();
  mockUseGuildSettings.mockReset();
  mockUseGuildRoles.mockReset();
  mockUseGuildCategories.mockReset();

  mockUseGuildRoles.mockReturnValue({ data: defaultRoles, isLoading: false });
  mockUseGuildCategories.mockReturnValue({ data: defaultCategories, isLoading: false });
});

afterEach(() => cleanup());

describe("GuildSettingsForm (redesign)", () => {
  it("redirects non-owner users away from the page", async () => {
    mockUseCurrentUser.mockReturnValue({ data: { ownedGuildIds: [], username: "joe" } });
    mockUseGuildSettings.mockReturnValue({ data: defaultSettings, isLoading: false });

    render(<GuildSettingsForm guildId="g1" />);
    await waitFor(() => expect(mockPush).toHaveBeenCalledWith("/"));
  });

  it("renders all base cards when settings load", async () => {
    mockUseCurrentUser.mockReturnValue({ data: { ownedGuildIds: ["g1"] } });
    mockUseGuildSettings.mockReturnValue({ data: defaultSettings, isLoading: false });

    render(<GuildSettingsForm guildId="g1" />);
    expect(await screen.findByRole("heading", { name: /rsvp emoji/i })).toBeVisible();
    expect(screen.getByRole("heading", { name: /categories & archive/i })).toBeVisible();
    expect(screen.getByRole("heading", { name: /primary location/i })).toBeVisible();
    expect(screen.getByRole("heading", { name: /roles & permissions/i })).toBeVisible();
    // Throttle visible because anyoneCanCreate defaults to true.
    expect(screen.getByRole("heading", { name: /creation throttle/i })).toBeVisible();
  });

  it("removes the throttle card when 'organisers' is selected", async () => {
    mockUseCurrentUser.mockReturnValue({ data: { ownedGuildIds: ["g1"] } });
    mockUseGuildSettings.mockReturnValue({ data: defaultSettings, isLoading: false });

    render(<GuildSettingsForm guildId="g1" />);
    await screen.findByRole("heading", { name: /roles & permissions/i });

    fireEvent.click(screen.getByRole("radio", { name: /organisers/i }));
    await waitFor(() =>
      expect(screen.queryByRole("heading", { name: /creation throttle/i })).toBeNull(),
    );
  });

  it("save bar transitions from all-saved to unsaved on edit and submits the full payload", async () => {
    mockUseCurrentUser.mockReturnValue({ data: { ownedGuildIds: ["g1"] } });
    mockUseGuildSettings.mockReturnValue({ data: defaultSettings, isLoading: false });
    mockUpdateGuildSettings.mockResolvedValueOnce(undefined);

    render(<GuildSettingsForm guildId="g1" />);
    await screen.findByRole("heading", { name: /rsvp emoji/i });

    // Initially all-saved.
    expect(screen.getByText(/all saved/i)).toBeInTheDocument();

    // Change archive_days to 30.
    fireEvent.click(screen.getByRole("radio", { name: "30 days" }));
    expect(await screen.findByText(/unsaved changes/i)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));
    await waitFor(() => expect(mockUpdateGuildSettings).toHaveBeenCalledTimes(1));
    const [guildId, payload] = mockUpdateGuildSettings.mock.calls[0];
    expect(guildId).toBe("g1");
    expect(payload.archiveDays).toBe(30);
    expect(payload.anyoneCanCreate).toBe(true);
    expect(payload.eventCreateRateLimitPerHour).toBe(5);
  });

  it("forces eventCreateRateLimitPerHour to null when organisers-only is selected on save", async () => {
    mockUseCurrentUser.mockReturnValue({ data: { ownedGuildIds: ["g1"] } });
    mockUseGuildSettings.mockReturnValue({ data: defaultSettings, isLoading: false });
    mockUpdateGuildSettings.mockResolvedValueOnce(undefined);

    render(<GuildSettingsForm guildId="g1" />);
    await screen.findByRole("heading", { name: /roles & permissions/i });

    fireEvent.click(screen.getByRole("radio", { name: /organisers/i }));
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(mockUpdateGuildSettings).toHaveBeenCalledTimes(1));
    expect(mockUpdateGuildSettings.mock.calls[0][1].eventCreateRateLimitPerHour).toBeNull();
    expect(mockUpdateGuildSettings.mock.calls[0][1].anyoneCanCreate).toBe(false);
  });

  it("shows loading state while settings are fetching", async () => {
    mockUseCurrentUser.mockReturnValue({ data: { ownedGuildIds: ["g1"] } });
    mockUseGuildSettings.mockReturnValue({ data: undefined, isLoading: true });

    render(<GuildSettingsForm guildId="g1" />);
    expect(screen.getByText(/loading…/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /save changes/i })).toBeNull();
  });
});
