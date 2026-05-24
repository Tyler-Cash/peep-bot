// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ErrorRef, formatCopyLine } from "@/components/ui/ErrorRef";
import type { ErrorRef as ErrorRefInfo } from "@/lib/api";

const fullRef: ErrorRefInfo = {
  traceId: "abc123def4567890",
  status: 500,
  method: "POST",
  path: "/event",
  timestamp: "2026-05-24T14:23:01.000Z",
};

afterEach(() => cleanup());

describe("formatCopyLine", () => {
  it("produces a single paste-ready line a dev can search on", () => {
    expect(formatCopyLine(fullRef)).toBe(
      "traceId=abc123def4567890 status=500 POST /event @ 2026-05-24T14:23:01.000Z",
    );
  });

  it("uses n/a placeholders when traceId/status are absent", () => {
    expect(
      formatCopyLine({ method: "GET", path: "/csrf", timestamp: "2026-05-24T00:00:00.000Z" }),
    ).toBe("traceId=n/a status=n/a GET /csrf @ 2026-05-24T00:00:00.000Z");
  });
});

describe("<ErrorRef>", () => {
  it("renders nothing when info is null", () => {
    const { container } = render(<ErrorRef info={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("shows the route, status and a short trace ref", () => {
    render(<ErrorRef info={fullRef} />);
    expect(screen.getByText(/POST \/event/)).toBeInTheDocument();
    expect(screen.getByText(/500/)).toBeInTheDocument();
    // traceId is truncated for display.
    expect(screen.getByText(/ref abc123de/)).toBeInTheDocument();
  });

  it("copies the full one-line ref to the clipboard", async () => {
    const user = userEvent.setup();
    // Assign after setup(): userEvent installs its own clipboard stub on setup.
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText },
      configurable: true,
    });

    render(<ErrorRef info={fullRef} />);
    await user.click(screen.getByRole("button", { name: /copy error details/i }));

    expect(writeText).toHaveBeenCalledWith(formatCopyLine(fullRef));
  });
});
