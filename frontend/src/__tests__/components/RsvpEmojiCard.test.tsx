// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, fireEvent, cleanup, within } from "@testing-library/react";
import { RsvpEmojiCard } from "@/components/guild/settings/RsvpEmojiCard";

afterEach(() => cleanup());

describe("RsvpEmojiCard", () => {
  it("renders three slots with the current selections", () => {
    render(
      <RsvpEmojiCard
        value={{ accept: "✅", decline: "❌", maybe: "❓" }}
        onChange={vi.fn()}
      />,
    );
    expect(screen.getByLabelText("going slot selection")).toHaveTextContent("✅");
    expect(screen.getByLabelText("not going slot selection")).toHaveTextContent("❌");
    expect(screen.getByLabelText("maybe slot selection")).toHaveTextContent("❓");
  });

  it("changes a slot when a swatch is clicked", () => {
    const onChange = vi.fn();
    render(
      <RsvpEmojiCard
        value={{ accept: "✅", decline: "❌", maybe: "❓" }}
        onChange={onChange}
      />,
    );
    const going = screen.getByLabelText("going swatches");
    fireEvent.click(within(going).getByText("👍"));
    expect(onChange).toHaveBeenCalledWith({ accept: "👍", decline: "❌", maybe: "❓" });
  });
});
