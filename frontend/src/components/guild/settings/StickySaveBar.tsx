"use client";

import { Chunky } from "@/components/ui/Chunky";

export function StickySaveBar({
  dirty,
  submitting,
  onDiscard,
  onSave,
}: {
  dirty: boolean;
  submitting: boolean;
  onDiscard: () => void;
  onSave: () => void;
}) {
  return (
    <div className="sticky bottom-6 mx-auto bg-white border-[1.5px] border-ink rounded-[16px] shadow-[4px_4px_0_#0E100D] px-5 py-3 flex items-center justify-between gap-3 z-10">
      <button
        type="button"
        onClick={onDiscard}
        disabled={!dirty}
        className="text-[14px] font-semibold text-mute hover:text-ink disabled:opacity-50"
      >
        discard
      </button>
      <span className="text-[13px] font-semibold text-mute flex items-center gap-2">
        <span
          aria-hidden
          className={"inline-block w-2 h-2 rounded-full " + (dirty ? "bg-leaf" : "bg-paper3")}
        />
        {dirty ? "unsaved changes" : "all saved"}
      </span>
      <Chunky
        type="button"
        variant="leaf"
        size="md"
        disabled={!dirty || submitting}
        onClick={onSave}
      >
        {submitting ? "saving…" : "save changes"}
      </Chunky>
    </div>
  );
}
