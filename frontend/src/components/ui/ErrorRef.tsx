"use client";

import { useState } from "react";
import type { ErrorRef as ErrorRefInfo } from "@/lib/api";

/**
 * The shared trace block shown beneath every surfaced error — inline and in toasts.
 * Gives a user enough to report a failure and a dev enough to find the exact request:
 * the route that failed, the HTTP status, the OpenTelemetry `traceId` (links to logs /
 * traces in Grafana), and when it happened.
 */
export function ErrorRef({ info }: { info: ErrorRefInfo | null }) {
  const [copied, setCopied] = useState(false);

  if (!info) return null;

  const route = `${info.method} ${info.path || "—"}`;
  const status = info.status != null ? ` · ${info.status}` : "";
  const time = formatTime(info.timestamp);
  const traceShort = info.traceId ? info.traceId.slice(0, 8) : null;

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(formatCopyLine(info));
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // Clipboard can be blocked (insecure context / permissions); the ref text is
      // still visible on screen, so a failed copy is not worth surfacing.
    }
  };

  return (
    <div className="mt-1.5 flex items-center justify-between gap-2 rounded-md border border-ink/20 bg-ink/[0.04] px-2 py-1 font-mono text-[11px] leading-tight text-ink/70">
      <div className="min-w-0">
        <div className="truncate font-semibold">
          {route}
          {status}
        </div>
        <div className="truncate">
          {traceShort ? <>ref {traceShort} · </> : null}
          {time}
        </div>
      </div>
      <button
        type="button"
        onClick={copy}
        aria-label="copy error details"
        className="shrink-0 rounded border border-ink/30 bg-white px-1.5 py-0.5 text-[10px] font-extrabold uppercase tracking-[0.06em] text-ink/70 hover:bg-paper2"
      >
        {copied ? "copied" : "copy"}
      </button>
    </div>
  );
}

function formatTime(ts: string): string {
  const d = new Date(ts);
  if (Number.isNaN(d.getTime())) return ts;
  return d.toLocaleTimeString();
}

/** One-line string a dev can paste straight into log/trace search. */
export function formatCopyLine(info: ErrorRefInfo): string {
  const parts = [
    `traceId=${info.traceId ?? "n/a"}`,
    `status=${info.status ?? "n/a"}`,
    `${info.method} ${info.path || "n/a"}`,
    `@ ${info.timestamp}`,
  ];
  return parts.join(" ");
}
