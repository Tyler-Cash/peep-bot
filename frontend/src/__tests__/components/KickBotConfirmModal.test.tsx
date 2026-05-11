// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, fireEvent, cleanup, waitFor } from "@testing-library/react";
import { KickBotConfirmModal } from "@/components/guild/settings/KickBotConfirmModal";

afterEach(() => cleanup());

describe("KickBotConfirmModal", () => {
  it("keeps confirm disabled until the typed name matches and then fires onConfirm", async () => {
    const onConfirm = vi.fn();
    render(<KickBotConfirmModal guildName="Porch Pigeons" onClose={vi.fn()} onConfirm={onConfirm} />);
    const confirm = screen.getByRole("button", { name: /kick peepbot/i });
    expect(confirm).toBeDisabled();
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "porch pigeons" } });
    expect(confirm).not.toBeDisabled();
    fireEvent.click(confirm);
    await waitFor(() => expect(onConfirm).toHaveBeenCalled());
  });

  it("trims and is case-insensitive", () => {
    render(<KickBotConfirmModal guildName="Porch Pigeons" onClose={vi.fn()} onConfirm={vi.fn()} />);
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "  PORCH PIGEONS  " } });
    expect(screen.getByRole("button", { name: /kick peepbot/i })).not.toBeDisabled();
  });
});
