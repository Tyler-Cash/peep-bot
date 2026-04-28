// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import React from "react";

// --- Module mocks --------------------------------------------------------
const mockPush = vi.fn();
const mockCreateEvent = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mockPush }),
}));

vi.mock("@/lib/hooks", () => ({
  createEvent: (...args: unknown[]) => mockCreateEvent(...args),
  useActiveGuild: () => ({
    id: "guild-1",
    name: "Test Guild",
    channel: "outings",
  }),
  useRecentLocations: () => [],
}));

// Replace heavy custom widgets with simple inputs we can drive directly.
// Use React.createElement to avoid TS parsing JSX inside vi.mock factories.
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

// Import AFTER mocks are registered.
import { CreateEventForm } from "@/components/event/CreateEventForm";

beforeEach(() => {
  mockPush.mockReset();
  mockCreateEvent.mockReset();
});

afterEach(() => {
  cleanup();
});

describe("CreateEventForm", () => {
  it("blocks submit when required event name is missing", async () => {
    const user = userEvent.setup();
    render(<CreateEventForm />);

    await user.click(screen.getByRole("button", { name: /post event/i }));

    expect(mockCreateEvent).not.toHaveBeenCalled();
    expect(mockPush).not.toHaveBeenCalled();
    // Native HTML5 validation should mark the input invalid.
    const nameInput = screen.getByPlaceholderText(/trivia at the dog/i);
    expect((nameInput as HTMLInputElement).validity.valid).toBe(false);
  });

  it("submits with the right payload and navigates to the new event", async () => {
    mockCreateEvent.mockResolvedValueOnce({ id: 42 });
    const user = userEvent.setup();
    render(<CreateEventForm />);

    await user.type(
      screen.getByPlaceholderText(/trivia at the dog/i),
      "Pub Quiz",
    );
    await user.type(screen.getByTestId("location"), "The Oak");
    // Capacity stepper: value is `0` initially; type "8" → "08" → Number→8
    const stepper = screen.getByTestId("stepper") as HTMLInputElement;
    await user.clear(stepper);
    await user.type(stepper, "8");

    await user.click(screen.getByRole("button", { name: /post event/i }));

    await waitFor(() => expect(mockCreateEvent).toHaveBeenCalledTimes(1));
    const [guildId, payload] = mockCreateEvent.mock.calls[0];
    expect(guildId).toBe("guild-1");
    expect(payload).toMatchObject({
      name: "Pub Quiz",
      description: "",
      location: "The Oak",
      capacity: 8,
    });
    // dateTime should be a valid ISO timestamp.
    expect(typeof payload.dateTime).toBe("string");
    expect(() => new Date(payload.dateTime)).not.toThrow();

    await waitFor(() => expect(mockPush).toHaveBeenCalledWith("/events/42"));
  });

  // NOTE: CreateEventForm does NOT currently render any error UI when
  // createEvent() rejects — it just lets the promise reject upward and
  // re-enables the submit button via try/finally. There is nothing
  // user-visible to assert on, so we don't ship a "shows error" test
  // that would have to lie about behavior the component lacks. This
  // gap is intentional and worth fixing in a follow-up feature.
});
