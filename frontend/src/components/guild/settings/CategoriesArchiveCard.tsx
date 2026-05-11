"use client";

import { ChipPicker, type ChipOption } from "@/components/ui/ChipPicker";
import { SegmentedSelector } from "@/components/ui/SegmentedSelector";

export function CategoriesArchiveCard({
  plannedCategoryId,
  archivedCategoryId,
  archiveDays,
  categories,
  ready,
  onPlannedChange,
  onArchivedChange,
  onArchiveDaysChange,
}: {
  plannedCategoryId: string | null;
  archivedCategoryId: string | null;
  archiveDays: number;
  categories: ChipOption[];
  ready: boolean;
  onPlannedChange: (id: string) => void;
  onArchivedChange: (id: string) => void;
  onArchiveDaysChange: (d: number) => void;
}) {
  return (
    <section className="bg-white border-[1.5px] border-ink rounded-[16px] shadow-[4px_4px_0_#0E100D] p-6">
      <h2 className="text-[24px] font-extrabold tracking-[-0.03em] lowercase">categories &amp; archive</h2>
      <p className="text-[13.5px] font-semibold text-mute mt-1">
        where new event channels live, and where they retire to.
      </p>
      <div className="flex flex-col gap-[18px] mt-4">
        <div>
          <p className="text-[11.5px] font-extrabold tracking-[0.18em] text-mute uppercase">
            planned events category
          </p>
          <ChipPicker
            value={plannedCategoryId}
            onChange={onPlannedChange}
            options={categories}
            disabledIds={[archivedCategoryId]}
            ready={ready}
            label="planned events category"
            prefix="#"
          />
          <p className="text-[12.5px] font-semibold text-mute mt-1">
            every new event becomes a channel inside this category.
          </p>
        </div>

        <div>
          <p className="text-[11.5px] font-extrabold tracking-[0.18em] text-mute uppercase">
            archived events category
          </p>
          <ChipPicker
            value={archivedCategoryId}
            onChange={onArchivedChange}
            options={categories}
            disabledIds={[plannedCategoryId]}
            ready={ready}
            label="archived events category"
            prefix="#"
          />
          <p className="text-[12.5px] font-semibold text-mute mt-1">past events move here.</p>
        </div>

        <div>
          <p className="text-[11.5px] font-extrabold tracking-[0.18em] text-mute uppercase">
            archive events after
          </p>
          <SegmentedSelector
            value={archiveDays}
            onChange={onArchiveDaysChange}
            ariaLabel="archive events after"
            options={[
              { value: 7, label: "7 days" },
              { value: 14, label: "14 days" },
              { value: 30, label: "30 days" },
              { value: 90, label: "90 days", defaultPill: true },
            ]}
          />
          <p className="text-[12.5px] font-semibold text-mute mt-2">
            after this many days past the event date, peepbot moves the channel into the archive
            category.
          </p>
        </div>
      </div>
    </section>
  );
}
