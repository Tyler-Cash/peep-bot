"use client";

import { useState } from "react";
import { Chunky } from "@/components/ui/Chunky";

export function KickBotConfirmModal({
  guildName,
  onClose,
  onConfirm,
}: {
  guildName: string;
  onClose: () => void;
  onConfirm: () => void | Promise<void>;
}) {
  const [typed, setTyped] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const matches = typed.trim().toLowerCase() === guildName.trim().toLowerCase();

  return (
    <div
      role="dialog"
      aria-modal="true"
      className="fixed inset-0 bg-ink/40 flex items-center justify-center z-30"
    >
      <div className="bg-paper border-[1.5px] border-danger rounded-[16px] shadow-[4px_4px_0_#DC2626] p-6 w-[420px]">
        <h2 className="text-[20px] font-extrabold tracking-[-0.02em] lowercase text-danger">
          kick peepbot
        </h2>
        <p className="mt-2 text-[14px] text-ink">
          type <strong>{guildName}</strong> to confirm. peepbot will leave the server, the events
          role will be deleted, and #outings will be removed. event history stays in the rewind.
        </p>
        <input
          type="text"
          value={typed}
          onChange={(e) => setTyped(e.target.value)}
          className="w-full mt-3 px-3 py-2 rounded-input border-[1.5px] border-ink"
          aria-label="confirm guild name"
        />
        <div className="mt-4 flex justify-end gap-2">
          <Chunky type="button" variant="paper" onClick={onClose}>
            cancel
          </Chunky>
          <Chunky
            type="button"
            variant="danger"
            disabled={!matches || submitting}
            onClick={async () => {
              setSubmitting(true);
              try {
                await onConfirm();
              } finally {
                setSubmitting(false);
              }
            }}
          >
            {submitting ? "kicking…" : "kick peepbot"}
          </Chunky>
        </div>
      </div>
    </div>
  );
}
