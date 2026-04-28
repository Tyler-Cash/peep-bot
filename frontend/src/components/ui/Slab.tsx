import clsx from "@/lib/clsx";
import type { ElementType, HTMLAttributes, ReactNode } from "react";

export function Slab({
  className,
  children,
  as,
  ...rest
}: HTMLAttributes<HTMLDivElement> & { children: ReactNode; as?: ElementType }) {
  const Tag: ElementType = as ?? "div";
  return (
    <Tag
      {...rest}
      className={clsx(
        "bg-white border-[1.5px] border-ink rounded-card shadow-rest",
        className,
      )}
    >
      {children}
    </Tag>
  );
}
