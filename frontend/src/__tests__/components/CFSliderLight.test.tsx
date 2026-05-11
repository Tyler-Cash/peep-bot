// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, fireEvent, cleanup } from "@testing-library/react";
import { CFSliderLight } from "@/components/ui/CFSliderLight";

afterEach(() => cleanup());

describe("CFSliderLight", () => {
  it("emits the new value on input change", () => {
    const onChange = vi.fn();
    render(<CFSliderLight value={5} min={1} max={10} onChange={onChange} />);
    const slider = screen.getByRole("slider");
    fireEvent.change(slider, { target: { value: "8" } });
    expect(onChange).toHaveBeenCalledWith(8);
  });

  it("renders tick row 1..10 with active tick highlighted", () => {
    render(<CFSliderLight value={3} min={1} max={10} onChange={vi.fn()} />);
    const three = screen.getByTestId("tick-3");
    const five = screen.getByTestId("tick-5");
    expect(three.className).toMatch(/leafDk/);
    expect(five.className).not.toMatch(/leafDk/);
  });
});
