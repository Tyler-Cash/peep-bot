export type NavTab = {
  label: string;
  href: string;
  // When true, the tab is only rendered for users with the bot-admin flag.
  requiresAdmin?: boolean;
};

export const NAV_TABS: NavTab[] = [
  { label: "events", href: "/" },
  { label: "gallery", href: "/gallery" },
  { label: "rewind", href: "/rewind" },
  { label: "admin", href: "/admin", requiresAdmin: true },
];

export function isAdminPath(pathname: string) {
  return pathname === "/admin" || pathname.startsWith("/admin/");
}

const SETTINGS_PATH_RE = /^\/guild\/[^/]+\/settings(?:\/.*)?$/;

export function isSettingsPath(pathname: string) {
  return SETTINGS_PATH_RE.test(pathname);
}

export function isTabActive(pathname: string, href: string) {
  return href === "/"
    ? pathname === "/"
    : pathname === href || pathname.startsWith(href + "/");
}

/**
 * Filter NAV_TABS by user role (admin) and per-guild feature flags.
 * Pass undefined features to show all feature-gated tabs (e.g. while loading).
 */
export function filterNavTabs(
  tabs: NavTab[],
  features: { immichEnabled?: boolean; rewindEnabled?: boolean } | undefined,
  user: { admin?: boolean } | undefined,
): NavTab[] {
  return tabs.filter((t) => {
    if (t.requiresAdmin && !user?.admin) return false;
    if (!features) return true;
    if (t.href === "/gallery") return features.immichEnabled !== false;
    if (t.href === "/rewind") return features.rewindEnabled !== false;
    return true;
  });
}
