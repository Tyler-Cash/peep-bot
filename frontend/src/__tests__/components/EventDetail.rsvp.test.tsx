// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import React from "react";

const mockMutate = vi.fn();
const mockSubmitRsvp = vi.fn();
const mockToastError = vi.fn();

const futureIso = new Date(Date.now() + 1000 * 60 * 60 * 24 * 7).toISOString();
const eventData = {
  id: "e1",
  name: "Pub Quiz",
  dateTime: futureIso,
  category: "trivia",
  displayState: "upcoming",
  state: "PLANNED",
  completed: false,
  accepted: [],
  maybe: [],
  declined: [],
  host: "Host",
  channelId: "c1",
  messageId: "m1",
};

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

vi.mock("next/image", () => ({
  default: (props: Record<string, unknown>) => React.createElement("img", props),
}));

vi.mock("@/lib/toast", () => ({
  toastError: (...args: unknown[]) => mockToastError(...args),
}));

vi.mock("@/lib/hooks", () => ({
  useEvent: () => ({ data: eventData, mutate: mockMutate, isLoading: false, error: undefined }),
  useCurrentUser: () => ({ data: { discordId: "u1", username: "Me" } }),
  useActiveGuild: () => ({ id: "g1", name: "Guild" }),
  submitRsvp: (...args: unknown[]) => mockSubmitRsvp(...args),
  cancelEvent: vi.fn(),
  createPrivateChannel: vi.fn(),
  recategorizeEvent: vi.fn(),
  removeAttendee: vi.fn(),
}));

import { EventDetail } from "@/components/event/EventDetail";

beforeEach(() => {
  mockMutate.mockClear();
  mockSubmitRsvp.mockClear();
  mockToastError.mockClear();
});

afterEach(() => cleanup());

describe("EventDetail RSVP failure", () => {
  it("reverts the optimistic update and toasts when the RSVP request fails", async () => {
    const { ApiError } = await import("@/lib/api");
    mockSubmitRsvp.mockRejectedValueOnce(
      new ApiError(500, { error: "boom", traceId: "t1" }, "boom", {
        method: "POST",
        path: "/event/e1/rsvp",
      }),
    );
    const user = userEvent.setup();
    render(<EventDetail id="e1" />);

    await user.click(screen.getByRole("button", { name: /going/i }));

    await waitFor(() => expect(mockToastError).toHaveBeenCalledTimes(1));
    // First mutate is the optimistic update (with an updater fn); a later bare mutate()
    // call triggers revalidation to roll the UI back to server truth.
    expect(mockMutate).toHaveBeenCalledWith();
  });
});
