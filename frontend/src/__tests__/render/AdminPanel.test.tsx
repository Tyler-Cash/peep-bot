// @vitest-environment jsdom
import { afterEach, describe, it, expect, vi } from "vitest";
import { cleanup, render, screen, waitFor, fireEvent } from "@testing-library/react";
import { AdminPanel } from "@/components/admin/AdminPanel";
import { SWRConfig } from "swr";

// Mock next/navigation so the panel doesn't error on useRouter
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
}));

// Render with a no-dedup SWR provider so each test is isolated
function renderWithSWR(ui: React.ReactElement) {
  return render(
    <SWRConfig value={{ provider: () => new Map(), dedupingInterval: 0 }}>
      {ui}
    </SWRConfig>,
  );
}

describe("AdminPanel", () => {
  afterEach(() => cleanup());

  it("renders the overview section by default for a bot admin", async () => {
    renderWithSWR(<AdminPanel />);

    // The overview hero copy is unique to the new layout
    await waitFor(
      () => {
        expect(
          screen.getByText(/everything's mostly fine/i),
        ).toBeInTheDocument();
      },
      { timeout: 3000 },
    );

    // Health row labels show up once /admin/health resolves
    await waitFor(
      () => {
        expect(screen.getByText("bot")).toBeInTheDocument();
        expect(screen.getByText("discord")).toBeInTheDocument();
      },
      { timeout: 3000 },
    );
  });

  it("switches sections when sub-nav tabs are clicked", async () => {
    renderWithSWR(<AdminPanel />);

    await waitFor(
      () => expect(screen.getByText(/everything's mostly fine/i)).toBeInTheDocument(),
      { timeout: 3000 },
    );

    fireEvent.click(screen.getByRole("button", { name: "toggles" }));
    await waitFor(() =>
      expect(screen.getByText(/what peepbot does here/i)).toBeInTheDocument(),
    );

    fireEvent.click(screen.getByRole("button", { name: "jobs" }));
    await waitFor(() =>
      expect(screen.getByText(/the schedule/i)).toBeInTheDocument(),
    );

    fireEvent.click(screen.getByRole("button", { name: "guilds" }));
    await waitFor(() =>
      expect(screen.getByText(/every server, at a glance/i)).toBeInTheDocument(),
    );
  });

  it("opens the replay modal from the sub-nav", async () => {
    renderWithSWR(<AdminPanel />);

    await waitFor(
      () => expect(screen.getByText(/everything's mostly fine/i)).toBeInTheDocument(),
      { timeout: 3000 },
    );

    fireEvent.click(screen.getByRole("button", { name: /^↻ replay…$/ }));
    await waitFor(() => {
      expect(screen.getByText(/rerun a lifecycle stage/i)).toBeInTheDocument();
    });
    // The modal renders the lifecycle stage picker — verify a couple of stage labels show up.
    expect(screen.getAllByText(/init channel/i).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/classify/i).length).toBeGreaterThan(0);
  });
});
