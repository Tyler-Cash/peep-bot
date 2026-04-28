"use client";

import clsx from "@/lib/clsx";
import type { ButtonHTMLAttributes, ReactNode } from "react";

type Variant = "leaf" | "ink" | "paper" | "discord" | "danger";
type Size = "sm" | "md" | "lg";

const variantClasses: Record<Variant, string> = {
  leaf: "bg-leaf text-ink border-ink hover:bg-[#6eb145]",
  ink: "bg-ink text-paper border-ink hover:bg-ink2",
  paper: "bg-paper text-ink border-ink hover:bg-paper2",
  discord: "bg-discord text-white border-ink hover:brightness-95",
  danger: "bg-[#dc2626] text-white border-[#991b1b] hover:bg-[#b91c1c]",
};

const sizeClasses: Record<Size, string> = {
  sm: "px-3.5 py-1.5 text-[15px]",
  md: "px-5 py-2 text-[17px]",
  lg: "px-6 py-2.5 text-[20px]",
};

export function Chunky({
  variant = "leaf",
  size = "md",
  className,
  children,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: Variant;
  size?: Size;
  children: ReactNode;
}) {
  return (
    <button
      {...rest}
      className={clsx(
        "inline-flex items-center gap-2 rounded-chip border-[1.5px] font-bold tracking-[-0.01em]",
        "shadow-rest active:shadow-press active:translate-x-[2px] active:translate-y-[2px] transition-[box-shadow,transform]",
        "disabled:opacity-60 disabled:pointer-events-none",
        variantClasses[variant],
        sizeClasses[size],
        className,
      )}
    >
      {children}
    </button>
  );
}
