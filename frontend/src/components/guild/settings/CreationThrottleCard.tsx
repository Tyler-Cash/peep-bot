"use client";

import { CFSliderLight } from "@/components/ui/CFSliderLight";

export function CreationThrottleCard({
  rateLimit,
  onRateLimitChange,
}: {
  rateLimit: number;
  onRateLimitChange: (n: number) => void;
}) {
  return (
    <section className="bg-white border-[1.5px] border-ink rounded-[16px] shadow-[4px_4px_0_#0E100D] p-6">
      <h2 className="text-[24px] font-extrabold tracking-[-0.03em] lowercase">creation throttle</h2>
      <p className="text-[13.5px] font-semibold text-mute mt-1">
        prevents one member from spamming new events. organisers always bypass this.
      </p>
      <div className="mt-4">
        <p className="text-[13.5px] font-semibold text-mute tabular-nums">
          <span className="text-[32px] font-extrabold text-ink tracking-[-0.03em] align-baseline">
            {rateLimit}
          </span>{" "}
          events / hr, per member
        </p>
        <div className="mt-3">
          <CFSliderLight value={rateLimit} min={1} max={10} onChange={onRateLimitChange} />
        </div>
      </div>
    </section>
  );
}
