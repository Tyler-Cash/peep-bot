// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, fireEvent, cleanup } from "@testing-library/react";
import { CategoriesArchiveCard } from "@/components/guild/settings/CategoriesArchiveCard";

const cats = [
  { id: "100", name: "Alpha" },
  { id: "200", name: "Beta" },
  { id: "300", name: "Gamma" },
];

afterEach(() => cleanup());

describe("CategoriesArchiveCard", () => {
  it("marks the planned-selected category as disabled in the archived picker", () => {
    render(
      <CategoriesArchiveCard
        plannedCategoryId="100"
        archivedCategoryId={null}
        archiveDays={90}
        categories={cats}
        ready
        onPlannedChange={vi.fn()}
        onArchivedChange={vi.fn()}
        onArchiveDaysChange={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByLabelText(/select archived events category/i));
    const alpha = screen.getByText("Alpha");
    expect(alpha.className).toMatch(/line-through/);
  });

  it("invokes onArchiveDaysChange when a segment is clicked", () => {
    const onArchiveDaysChange = vi.fn();
    render(
      <CategoriesArchiveCard
        plannedCategoryId={null}
        archivedCategoryId={null}
        archiveDays={90}
        categories={cats}
        ready
        onPlannedChange={vi.fn()}
        onArchivedChange={vi.fn()}
        onArchiveDaysChange={onArchiveDaysChange}
      />,
    );
    fireEvent.click(screen.getByRole("radio", { name: "7 days" }));
    expect(onArchiveDaysChange).toHaveBeenCalledWith(7);
  });

  it("flags 90 days with the DEFAULT pill", () => {
    render(
      <CategoriesArchiveCard
        plannedCategoryId={null}
        archivedCategoryId={null}
        archiveDays={90}
        categories={cats}
        ready
        onPlannedChange={vi.fn()}
        onArchivedChange={vi.fn()}
        onArchiveDaysChange={vi.fn()}
      />,
    );
    expect(screen.getByText(/default/i)).toBeInTheDocument();
  });
});
