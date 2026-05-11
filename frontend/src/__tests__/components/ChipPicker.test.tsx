// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, fireEvent, cleanup } from "@testing-library/react";
import { ChipPicker } from "@/components/ui/ChipPicker";

const options = [
  { id: "1", name: "Aardvark" },
  { id: "2", name: "Mango" },
  { id: "3", name: "Zebra" },
];

afterEach(() => cleanup());

describe("ChipPicker", () => {
  it("renders the selected option's name", () => {
    render(
      <ChipPicker value="2" onChange={vi.fn()} options={options} ready label="Notification role" />,
    );
    expect(screen.getByRole("button", { name: /mango/i })).toBeInTheDocument();
  });

  it("opens the option list on click and selects an option", () => {
    const onChange = vi.fn();
    render(<ChipPicker value="1" onChange={onChange} options={options} ready label="x" />);
    fireEvent.click(screen.getByRole("button", { name: /aardvark/i }));
    fireEvent.click(screen.getByText("Zebra"));
    expect(onChange).toHaveBeenCalledWith("3");
  });

  it("shows skeleton chips while not ready", () => {
    render(<ChipPicker value={null} onChange={vi.fn()} options={[]} ready={false} label="x" />);
    expect(screen.getByText(/syncing with discord/i)).toBeInTheDocument();
  });

  it("renders disabled options with strikethrough and ignores clicks on them", () => {
    const onChange = vi.fn();
    render(
      <ChipPicker
        value={null}
        onChange={onChange}
        options={options}
        disabledIds={["2"]}
        ready
        label="x"
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: /select x/i }));
    const disabled = screen.getByText("Mango");
    expect(disabled.className).toMatch(/line-through/);
    fireEvent.click(disabled);
    expect(onChange).not.toHaveBeenCalled();
  });
});
