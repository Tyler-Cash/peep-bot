import { Peepo } from "@/components/Peepo";

export function LoadingOverlay() {
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center"
      style={{ background: "rgba(247,243,234,0.82)", backdropFilter: "blur(6px)" }}
    >
      <div className="flex flex-col items-center gap-5 rounded-[18px] border-[1.5px] border-ink bg-paper px-8 py-7 shadow-chunky-lg max-w-[360px] text-center">
        <span className="inline-flex items-center justify-center w-[78px] h-[78px] rounded-full bg-leaf border-[1.5px] border-ink shadow-chunky-md">
          <Peepo size={48} />
        </span>
        <div className="flex gap-2">
          <span className="w-2 h-2 rounded-full bg-ink animate-pb-bounce" style={{ animationDelay: "0s" }} />
          <span className="w-2 h-2 rounded-full bg-ink animate-pb-bounce" style={{ animationDelay: "0.15s" }} />
          <span className="w-2 h-2 rounded-full bg-ink animate-pb-bounce" style={{ animationDelay: "0.3s" }} />
        </div>
        <div>
          <p className="text-[20px] font-extrabold tracking-[-0.02em]">
            hopping over to Discord…
          </p>
          <p className="mt-1 text-[13.5px] text-mute">
            sign in with your Discord account to start posting and RSVPing to events.
          </p>
        </div>
      </div>
    </div>
  );
}
