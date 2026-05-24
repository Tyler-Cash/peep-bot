// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

const { custom, dismiss } = vi.hoisted(() => ({
  custom: vi.fn(),
  dismiss: vi.fn(),
}));

vi.mock("sonner", () => ({ toast: { custom, dismiss } }));

import { toastError } from "@/lib/toast";
import { ApiError, BackendUnreachable, UnauthorizedError } from "@/lib/api";

afterEach(() => {
  cleanup();
  custom.mockClear();
});

describe("toastError", () => {
  it("renders a toast carrying the friendly message and the trace ref", () => {
    const e = new ApiError(
      500,
      { error: "leak", traceId: "trace-abc-123" },
      "leak",
      { method: "POST", path: "/event" },
    );
    toastError(e);

    expect(custom).toHaveBeenCalledTimes(1);
    const renderFn = custom.mock.calls[0][0] as (id: string) => React.ReactElement;
    render(<>{renderFn("toast-1")}</>);

    expect(screen.getByText(/something went wrong/i)).toBeInTheDocument();
    expect(screen.getByText(/ref trace-ab/)).toBeInTheDocument();
    expect(screen.getByText(/POST \/event/)).toBeInTheDocument();
  });

  it("shows a connection message (no trace ref) when the backend is unreachable", () => {
    toastError(new BackendUnreachable({ method: "GET", path: "/event" }));
    const renderFn = custom.mock.calls[0][0] as (id: string) => React.ReactElement;
    render(<>{renderFn("toast-2")}</>);
    expect(screen.getByText(/can't reach the server/i)).toBeInTheDocument();
  });

  it("no-ops on UnauthorizedError (handled by redirect)", () => {
    toastError(new UnauthorizedError());
    expect(custom).not.toHaveBeenCalled();
  });

  it("no-ops on 429 (the rate-limit countdown toast is the canonical surface)", () => {
    toastError(new ApiError(429, null, "too many", { method: "POST", path: "/event" }));
    expect(custom).not.toHaveBeenCalled();
  });
});
