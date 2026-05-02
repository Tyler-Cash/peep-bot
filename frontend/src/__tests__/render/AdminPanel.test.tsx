// @vitest-environment jsdom
import { describe, it, expect, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
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
  it("shows the guild table when user is bot admin", async () => {
    renderWithSWR(<AdminPanel />);

    // The MSW mock server returns admin: true for currentUser and one guild
    await waitFor(
      () => {
        expect(screen.getByRole("table")).toBeInTheDocument();
      },
      { timeout: 3000 },
    );

    // The guild row should be present
    expect(screen.getByRole("table")).toBeInTheDocument();
    // Three feature checkboxes per row (Immich, Google Places, Rewind)
    const checkboxes = screen.getAllByRole("checkbox");
    expect(checkboxes.length).toBeGreaterThanOrEqual(3);
  });
});
