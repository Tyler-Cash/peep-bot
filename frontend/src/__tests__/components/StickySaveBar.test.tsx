// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, fireEvent, cleanup } from "@testing-library/react";
import { StickySaveBar } from "@/components/guild/settings/StickySaveBar";

afterEach(() => cleanup());

describe("StickySaveBar", () => {
  it("shows all-saved copy when not dirty and disables save", () => {
    render(<StickySaveBar dirty={false} onDiscard={vi.fn()} onSave={vi.fn()} submitting={false} />);
    expect(screen.getByText(/all saved/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /save changes/i })).toBeDisabled();
  });

  it("shows unsaved-changes copy when dirty and routes onSave", () => {
    const onSave = vi.fn();
    render(<StickySaveBar dirty onDiscard={vi.fn()} onSave={onSave} submitting={false} />);
    expect(screen.getByText(/unsaved changes/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));
    expect(onSave).toHaveBeenCalled();
  });
});
