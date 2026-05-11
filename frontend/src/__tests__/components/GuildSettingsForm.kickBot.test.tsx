// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent, cleanup, waitFor } from "@testing-library/react";
import React from "react";

const mockPush = vi.fn();
const mockKickBotFromGuild = vi.fn();
const mockUseCurrentUser = vi.fn();
const mockUseGuildSettings = vi.fn();
const mockUseGuildRoles = vi.fn();
const mockUseGuildCategories = vi.fn();

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: mockPush }) }));
vi.mock("next/link", () => ({
  default: function Link({ children, href }: { children: React.ReactNode; href: string }) {
    return React.createElement("a", { href }, children);
  },
}));
vi.mock("@/lib/hooks", () => ({
  updateGuildSettings: vi.fn(),
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
  LocationAutocomplete: () => null,
}));

import { GuildSettingsForm } from "@/components/guild/GuildSettingsForm";

const baseSettings = {
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

beforeEach(() => {
  mockPush.mockReset();
  mockKickBotFromGuild.mockReset();
  mockUseCurrentUser.mockReturnValue({ data: { ownedGuildIds: ["g1"] } });
  mockUseGuildSettings.mockReturnValue({ data: baseSettings, isLoading: false });
  mockUseGuildRoles.mockReturnValue({ data: [], isLoading: false });
  mockUseGuildCategories.mockReturnValue({ data: [], isLoading: false });
});

afterEach(() => cleanup());

describe("GuildSettingsForm kick-bot", () => {
  it("opens the modal, gates submit on typed name, and calls the kick mutator", async () => {
    mockKickBotFromGuild.mockResolvedValue(undefined);
    render(<GuildSettingsForm guildId="g1" />);

    // The "kick peepbot" trigger button is in the danger zone card.
    const triggers = await screen.findAllByRole("button", { name: /kick peepbot/i });
    fireEvent.click(triggers[0]);

    // Modal opens with a typed-confirmation input.
    const input = screen.getByRole("textbox", { name: /confirm guild name/i });
    // The modal's confirm button is disabled until the typed value matches.
    const confirm = screen.getAllByRole("button", { name: /^kick peepbot$/i })[1];
    expect(confirm).toBeDisabled();

    fireEvent.change(input, { target: { value: "porch pigeons" } });
    expect(confirm).not.toBeDisabled();

    fireEvent.click(confirm);
    await waitFor(() => expect(mockKickBotFromGuild).toHaveBeenCalledTimes(1));
    expect(mockKickBotFromGuild.mock.calls[0]).toEqual(["g1", "Porch Pigeons"]);
    await waitFor(() => expect(mockPush).toHaveBeenCalledWith("/"));
  });
});
