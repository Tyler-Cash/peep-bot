// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import React from "react";

const mockPush = vi.fn();
const mockUpdateEvent = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mockPush }),
}));

const eventFixture = {
  id: 7,
  name: "existing event",
  description: "details",
  location: "Old Place",
  locationPlaceId: "",
  capacity: 5,
  dateTime: new Date(Date.now() + 1000 * 60 * 60 * 24).toISOString(),
};

vi.mock("@/lib/hooks", () => ({
  updateEvent: (...args: unknown[]) => mockUpdateEvent(...args),
  useActiveGuild: () => ({
    id: "guild-1",
    name: "Test Guild",
    channel: "outings",
  }),
  useEvent: () => ({ data: eventFixture, isLoading: false }),
  useRecentLocations: () => [],
}));

function textInput(testid: string) {
  return function Mock({
    value,
    onChange,
  }: {
    value: string | null;
    onChange: (v: string) => void;
  }) {
    return React.createElement("input", {
      "data-testid": testid,
      value: value ?? "",
      onChange: (e: React.ChangeEvent<HTMLInputElement>) =>
        onChange(e.target.value),
    });
  };
}

vi.mock("@/components/ui/DateTimePicker", () => ({
  DatePicker: textInput("datepicker"),
  TimePicker: textInput("timepicker"),
}));

vi.mock("@/components/ui/LocationAutocomplete", () => ({
  LocationAutocomplete: textInput("location"),
}));

vi.mock("@/components/ui/Stepper", () => ({
  Stepper: function Stepper({
    value,
    onChange,
  }: {
    value: number;
    onChange: (v: number) => void;
  }) {
    return React.createElement("input", {
      "data-testid": "stepper",
      type: "number",
      value,
      onChange: (e: React.ChangeEvent<HTMLInputElement>) =>
        onChange(Number(e.target.value)),
    });
  },
}));

import { EditEventForm } from "@/components/event/EditEventForm";

beforeEach(() => {
  mockPush.mockReset();
  mockUpdateEvent.mockReset();
});

afterEach(() => {
  cleanup();
});

describe("EditEventForm", () => {
  it("renders an inline error when updateEvent rejects with ApiError", async () => {
    const { ApiError } = await import("@/lib/api");
    mockUpdateEvent.mockRejectedValueOnce(
      new ApiError(422, { message: "capacity must be positive" }, "capacity must be positive"),
    );
    const user = userEvent.setup();
    render(<EditEventForm id="7" />);

    await user.click(screen.getByRole("button", { name: /save changes/i }));

    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toMatch(/capacity must be positive/i);
    expect(mockPush).not.toHaveBeenCalled();
    const btn = screen.getByRole("button", {
      name: /save changes/i,
    }) as HTMLButtonElement;
    expect(btn.disabled).toBe(false);
  });

  it("clears a stale error and navigates on a successful retry", async () => {
    const { ApiError } = await import("@/lib/api");
    mockUpdateEvent
      .mockRejectedValueOnce(new ApiError(500, null, "boom"))
      .mockResolvedValueOnce(undefined);
    const user = userEvent.setup();
    render(<EditEventForm id="7" />);

    await user.click(screen.getByRole("button", { name: /save changes/i }));
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toMatch(/boom/i);

    await user.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(mockPush).toHaveBeenCalledWith("/events/7"));
    expect(screen.queryByRole("alert")).toBeNull();
  });
});
