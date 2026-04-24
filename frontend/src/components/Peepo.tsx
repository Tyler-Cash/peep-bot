type PeepoProps = { size?: number; hue?: string; className?: string };

export function Peepo({ size = 28, hue = "#7BC24F", className }: PeepoProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 40 40"
      className={className}
      aria-hidden
      style={{ display: "block" }}
    >
      <ellipse cx="20" cy="22" rx="15" ry="13" fill={hue} />
      <ellipse cx="20" cy="26" rx="8" ry="5" fill="#fff" opacity="0.18" />
      <circle cx="13.5" cy="13" r="5" fill={hue} />
      <circle cx="26.5" cy="13" r="5" fill={hue} />
      <path d="M11 13q2.5 2 5 0" stroke="#0E100D" strokeWidth="1.3" fill="none" strokeLinecap="round" />
      <path d="M24 13q2.5 2 5 0" stroke="#0E100D" strokeWidth="1.3" fill="none" strokeLinecap="round" />
      <path d="M16.5 23q3.5 2.2 7 0" stroke="#0E100D" strokeWidth="1.4" fill="none" strokeLinecap="round" />
      <circle cx="11.5" cy="20" r="1.4" fill="#F4A9B6" opacity="0.75" />
      <circle cx="28.5" cy="20" r="1.4" fill="#F4A9B6" opacity="0.75" />
    </svg>
  );
}

export function PeepoSleep({ size = 80, hue = "#7BC24F" }: PeepoProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 80 80" aria-hidden>
      <ellipse cx="40" cy="50" rx="30" ry="22" fill={hue} />
      <circle cx="28" cy="36" r="9" fill={hue} />
      <circle cx="52" cy="36" r="9" fill={hue} />
      <path d="M23 36q5 3 10 0" stroke="#0E100D" strokeWidth="1.8" fill="none" strokeLinecap="round" />
      <path d="M47 36q5 3 10 0" stroke="#0E100D" strokeWidth="1.8" fill="none" strokeLinecap="round" />
      <path d="M33 52q7 4 14 0" stroke="#0E100D" strokeWidth="2" fill="none" strokeLinecap="round" />
      <text x="60" y="22" fontFamily="'Space Grotesk', sans-serif" fontSize="12" fontWeight="700" fill="#0E100D" opacity="0.7">
        z
      </text>
      <text x="66" y="14" fontFamily="'Space Grotesk', sans-serif" fontSize="8" fontWeight="700" fill="#0E100D" opacity="0.5">
        z
      </text>
    </svg>
  );
}
