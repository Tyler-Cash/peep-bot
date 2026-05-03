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

/** Filter NAV_TABS by per-guild feature flags. Pass undefined features to show all tabs (e.g. while loading). */
export function filterNavTabs(
  tabs: NavTab[],
  features: { immichEnabled?: boolean; rewindEnabled?: boolean } | undefined,
): NavTab[] {
  if (!features) return tabs;
  return tabs.filter((t) => {
    if (t.href === "/gallery") return features.immichEnabled !== false;
    if (t.href === "/rewind") return features.rewindEnabled !== false;
    return true;
  });
}
