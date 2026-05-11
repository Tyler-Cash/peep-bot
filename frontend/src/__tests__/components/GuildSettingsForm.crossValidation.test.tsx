// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent, cleanup, waitFor } from "@testing-library/react";
import React from "react";

const mockPush = vi.fn();
const mockUpdateGuildSettings = vi.fn();
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
  plannedCategoryId: "100",
  archivedCategoryId: null,
  archiveDays: 90,
  anyoneCanCreate: true,
};

beforeEach(() => {
  mockPush.mockReset();
  mockUseCurrentUser.mockReturnValue({ data: { ownedGuildIds: ["g1"] } });
  mockUseGuildSettings.mockReturnValue({ data: baseSettings, isLoading: false });
  mockUseGuildRoles.mockReturnValue({ data: [], isLoading: false });
  mockUseGuildCategories.mockReturnValue({
    data: [
      { id: "100", name: "Active events" },
      { id: "200", name: "Archive" },
    ],
    isLoading: false,
  });
});

afterEach(() => cleanup());

describe("GuildSettingsForm cross-validation", () => {
  it("marks the planned-selected category as disabled in the archived picker", async () => {
    render(<GuildSettingsForm guildId="g1" />);
    await screen.findByRole("heading", { name: /categories & archive/i });

    fireEvent.click(screen.getByLabelText(/select archived events category/i));

    await waitFor(() => {
      const matches = screen.getAllByText("Active events");
      const struck = matches.find((el) => el.className.includes("line-through"));
      expect(struck).toBeTruthy();
    });
  });
});
