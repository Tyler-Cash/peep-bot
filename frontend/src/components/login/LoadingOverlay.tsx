import { Peepo } from "@/components/Peepo";

export function LoadingOverlay() {
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center"
      style={{ background: "rgba(247,243,234,0.82)", backdropFilter: "blur(6px)" }}
    >
      <div className="flex flex-col items-center rounded-hero border-[1.5px] border-ink bg-white px-9 py-8 shadow-hero max-w-[380px] text-center">
        <div className="relative mb-[28px]">
          <span className="inline-flex items-center justify-center w-[78px] h-[78px] rounded-full bg-leaf border-[1.5px] border-ink shadow-rest">
            <Peepo size={56} />
          </span>
          <div className="absolute -bottom-[18px] left-0 right-0 flex justify-center gap-[5px]">
            {[0, 1, 2].map((i) => (
              <span
                key={i}
                className="w-[7px] h-[7px] rounded-full bg-ink animate-pb-bounce"
                style={{ animationDelay: `${i * 120}ms` }}
              />
            ))}
          </div>
        </div>
        <p className="text-[20px] font-extrabold tracking-[-0.02em] text-ink mb-1.5">
          hopping over to Discord…
        </p>
        <p className="text-[13.5px] text-mute leading-[1.45]">
          sign in with your Discord account to start posting and RSVPing to events.
        </p>
      </div>
    </div>
  );
}
