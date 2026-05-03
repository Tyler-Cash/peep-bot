// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, trapConsoleError } from "../setup/render";
import { AddServerModal } from "@/components/nav/AddServerModal";

describe("AddServerModal", () => {
  afterEach(() => cleanup());

  it("renders the permissions table from /install-url", async () => {
    const { errors, restore } = trapConsoleError();
    try {
      renderWithProviders(<AddServerModal open onClose={() => {}} />);

      await screen.findByText(/manage roles/i, undefined, { timeout: 3000 });
      expect(
        screen.getByText(/per-event accepted \/ declined \/ maybe roles/i),
      ).toBeTruthy();
    } finally {
      restore();
    }
    expect(errors).toEqual([]);
  });

  it("opens a popup window when Continue is clicked", async () => {
    const openSpy = vi.spyOn(window, "open").mockReturnValue(null);
    renderWithProviders(<AddServerModal open onClose={() => {}} />);

    const button = await screen.findByRole(
      "button",
      { name: /continue to discord/i },
      { timeout: 3000 },
    );
    await userEvent.click(button);

    expect(openSpy).toHaveBeenCalledWith(
      expect.stringContaining("discord.com/api/oauth2/authorize"),
      "peep-install",
      expect.stringContaining("popup"),
    );
    expect(
      screen.getByRole("button", { name: /waiting for install/i }),
    ).toBeTruthy();
    openSpy.mockRestore();
  });

  it("dismisses on Escape", async () => {
    const onClose = vi.fn();
    renderWithProviders(<AddServerModal open onClose={onClose} />);
    fireEvent.keyDown(document, { key: "Escape" });
    expect(onClose).toHaveBeenCalled();
  });
});
