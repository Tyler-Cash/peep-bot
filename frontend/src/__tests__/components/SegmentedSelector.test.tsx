// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, fireEvent, cleanup } from "@testing-library/react";
import { SegmentedSelector } from "@/components/ui/SegmentedSelector";

afterEach(() => cleanup());

describe("SegmentedSelector", () => {
  it("renders options and emits on click", () => {
    const onChange = vi.fn();
    render(
      <SegmentedSelector
        value={7}
        onChange={onChange}
        options={[
          { value: 7, label: "7 days" },
          { value: 14, label: "14 days" },
          { value: 90, label: "90 days", defaultPill: true },
        ]}
      />,
    );
    fireEvent.click(screen.getByRole("radio", { name: /14 days/i }));
    expect(onChange).toHaveBeenCalledWith(14);
  });

  it("marks the active option with aria-checked=true", () => {
    render(
      <SegmentedSelector
        value={7}
        onChange={vi.fn()}
        options={[
          { value: 7, label: "7 days" },
          { value: 14, label: "14 days" },
        ]}
      />,
    );
    expect(screen.getByRole("radio", { name: /7 days/i })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("radio", { name: /14 days/i })).toHaveAttribute("aria-checked", "false");
  });

  it("renders defaultPill marker on flagged option", () => {
    render(
      <SegmentedSelector
        value={7}
        onChange={vi.fn()}
        options={[
          { value: 7, label: "7 days" },
          { value: 90, label: "90 days", defaultPill: true },
        ]}
      />,
    );
    expect(screen.getByText(/default/i)).toBeInTheDocument();
  });
});
