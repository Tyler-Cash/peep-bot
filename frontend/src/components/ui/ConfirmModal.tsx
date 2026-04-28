"use client";

import { useState } from "react";
import { Chunky } from "@/components/ui/Chunky";

interface ConfirmModalProps {
  title: string;
  message: React.ReactNode;
  confirmLabel: string;
  confirmVariant?: "leaf" | "danger";
  onConfirm: () => void | Promise<void>;
  onCancel: () => void;
}

export function ConfirmModal({
  title,
  message,
  confirmLabel,
  confirmVariant = "leaf",
  onConfirm,
  onCancel,
}: ConfirmModalProps) {
  const [busy, setBusy] = useState(false);

  const handleConfirm = async () => {
    setBusy(true);
    try {
      await onConfirm();
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-ink/40" onClick={onCancel} />
      <div className="relative z-10 w-full max-w-[420px] rounded-hero border-[1.5px] border-ink bg-paper p-6 shadow-hero">
        <h2 className="text-[22px] font-extrabold tracking-[-0.02em]">{title}</h2>
        <div className="mt-2 text-[15px] text-ink2 leading-[1.6]">{message}</div>
        <div className="mt-5 flex gap-2 justify-end">
          <Chunky variant="paper" size="sm" onClick={onCancel} disabled={busy}>
            nevermind
          </Chunky>
          <Chunky variant={confirmVariant} size="sm" onClick={handleConfirm} disabled={busy}>
            {busy ? "working…" : confirmLabel}
          </Chunky>
        </div>
      </div>
    </div>
  );
}
