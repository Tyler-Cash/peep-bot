"use client";

import { useEffect, useRef, useState } from "react";
import { Chunky } from "@/components/ui/Chunky";
import { useInstallUrl } from "@/lib/hooks";

export function AddServerModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { data, isLoading } = useInstallUrl();
  const closeBtnRef = useRef<HTMLButtonElement>(null);
  const previouslyFocused = useRef<HTMLElement | null>(null);
  const [launched, setLaunched] = useState(false);

  useEffect(() => {
    if (!open) {
      setLaunched(false);
      return;
    }
    previouslyFocused.current = (document.activeElement as HTMLElement) ?? null;
    closeBtnRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("keydown", onKey);
      previouslyFocused.current?.focus?.();
    };
  }, [open, onClose]);

  if (!open) return null;

  const launchPopup = () => {
    if (!data?.url) return;
    window.open(data.url, "peep-install", "popup,width=520,height=720");
    setLaunched(true);
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="add-server-title"
      className="fixed inset-0 z-50 flex items-center justify-center px-4"
    >
      <div
        className="absolute inset-0 bg-ink/40"
        onClick={onClose}
        aria-hidden
      />
      <div className="relative w-full max-w-[520px] rounded-card border-[1.5px] border-ink bg-paper shadow-rest overflow-hidden">
        <div className="flex items-start justify-between px-5 pt-4 pb-3 border-b-[1px] border-ink/10">
          <h2
            id="add-server-title"
            className="text-[20px] font-extrabold tracking-[-0.02em] leading-tight"
          >
            Add Peep Bot to a server
          </h2>
          <button
            ref={closeBtnRef}
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="text-mute hover:text-ink text-[20px] leading-none p-1 -mr-1 -mt-1 rounded-chip hover:bg-paper2"
          >
            ×
          </button>
        </div>

        <div className="px-5 py-4">
          <p className="text-[14px] text-mute mb-4">
            We&apos;ll open Discord in a popup. Here&apos;s what Peep Bot will be allowed to do, and why.{" "}
            <a
              href="https://github.com/Tyler-Cash/peep-bot/blob/main/docs/restricted-permissions.md"
              target="_blank"
              rel="noopener noreferrer"
              className="font-semibold text-ink underline hover:no-underline"
            >
              Want to install with limited permissions?
            </a>
          </p>

          {isLoading || !data ? (
            <div className="h-[180px] rounded-card bg-paper2 animate-pulse" aria-hidden />
          ) : (
            <table className="w-full text-left border-[1px] border-ink/15 rounded-card overflow-hidden">
              <thead className="bg-paper2 text-[11px] font-extrabold tracking-[0.16em] text-mute uppercase">
                <tr>
                  <th className="px-3 py-2 w-[38%]">Permission</th>
                  <th className="px-3 py-2">Why we need it</th>
                </tr>
              </thead>
              <tbody className="text-[14px]">
                {data.permissions.map((p, i) => (
                  <tr
                    key={p.name}
                    className={i % 2 === 1 ? "bg-paper2/60" : undefined}
                  >
                    <td className="px-3 py-2 font-semibold align-top">{p.name}</td>
                    <td className="px-3 py-2 text-mute align-top">{p.reason}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        <div className="flex flex-col gap-2 px-5 pb-5">
          <div className="flex items-center justify-end gap-3">
            <button
              type="button"
              onClick={onClose}
              className="text-[15px] font-semibold text-mute hover:text-ink px-3 py-2"
            >
              Cancel
            </button>
            <Chunky
              variant="discord"
              size="md"
              onClick={launchPopup}
              disabled={!data?.url}
            >
              {launched ? "Waiting for install…" : "Continue to Discord"}
            </Chunky>
          </div>
          {launched && (
            <p className="text-[12px] text-mute text-right">
              Close the Discord popup once you&apos;ve added the bot.
            </p>
          )}
        </div>
      </div>
    </div>
  );
}
