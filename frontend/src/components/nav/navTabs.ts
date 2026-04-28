export type NavTab = { label: string; href: string };

export const NAV_TABS: NavTab[] = [
  { label: "events", href: "/" },
  { label: "gallery", href: "/gallery" },
  { label: "rewind", href: "/rewind" },
];

export function isTabActive(pathname: string, href: string) {
  return href === "/"
    ? pathname === "/"
    : pathname === href || pathname.startsWith(href + "/");
}
