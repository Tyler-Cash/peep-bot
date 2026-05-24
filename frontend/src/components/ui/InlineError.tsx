"use client";

import type { ErrorRef as ErrorRefInfo } from "@/lib/api";
import { ErrorRef } from "./ErrorRef";

/**
 * The inline error box used next to a form or control when a specific request fails.
 * Shows the friendly message plus the trace {@link ErrorRef} so a user can report it.
 */
export function InlineError({
  message,
  info,
}: {
  message: string;
  info?: ErrorRefInfo | null;
}) {
  return (
    <div
      role="alert"
      className="rounded-chip border-[1.5px] border-ink bg-rose-50 text-ink px-[14px] py-2.5 text-[14.5px] font-semibold leading-[1.4]"
    >
      {message}
      {info ? <ErrorRef info={info} /> : null}
    </div>
  );
}
