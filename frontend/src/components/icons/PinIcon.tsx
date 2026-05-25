import type { SVGProps } from "react";

export function PinIcon({
  size = 24,
  ...props
}: { size?: number } & SVGProps<SVGSVGElement>) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 30"
      width={size}
      height={size * 1.25}
      aria-hidden
      {...props}
    >
      <path
        d="M12 1.2c-5.6 0-9.6 4-9.6 9.4 0 5 4.7 9.2 8.4 16 .5.9 1.9.9 2.4 0 3.7-6.8 8.4-11 8.4-16 0-5.4-4-9.4-9.6-9.4z"
        fill="#7BC24F"
        stroke="#0E100D"
        strokeWidth={1.8}
        strokeLinejoin="round"
      />
      <circle
        cx={12}
        cy={10.6}
        r={3.2}
        fill="#FFFFFF"
        stroke="#0E100D"
        strokeWidth={1.4}
      />
    </svg>
  );
}
