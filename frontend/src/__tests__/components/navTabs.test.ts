import { describe, it, expect } from "vitest";
import {
  NAV_TABS,
  filterNavTabs,
  isAdminPath,
  isSettingsPath,
} from "@/components/nav/navTabs";

describe("filterNavTabs", () => {
  it("hides the admin tab from non-admin users", () => {
    const tabs = filterNavTabs(NAV_TABS, undefined, { admin: false });
    expect(tabs.map((t) => t.href)).not.toContain("/admin");
  });

  it("hides the admin tab when user is undefined (still loading)", () => {
    const tabs = filterNavTabs(NAV_TABS, undefined, undefined);
    expect(tabs.map((t) => t.href)).not.toContain("/admin");
  });

  it("shows the admin tab to admin users", () => {
    const tabs = filterNavTabs(NAV_TABS, undefined, { admin: true });
    expect(tabs.map((t) => t.href)).toContain("/admin");
  });

  it("hides /gallery when immich is disabled", () => {
    const tabs = filterNavTabs(
      NAV_TABS,
      { immichEnabled: false, rewindEnabled: true },
      { admin: false },
    );
    expect(tabs.map((t) => t.href)).not.toContain("/gallery");
    expect(tabs.map((t) => t.href)).toContain("/rewind");
  });

  it("hides /rewind when rewind is disabled", () => {
    const tabs = filterNavTabs(
      NAV_TABS,
      { immichEnabled: true, rewindEnabled: false },
      { admin: false },
    );
    expect(tabs.map((t) => t.href)).not.toContain("/rewind");
  });

  it("admin gating is independent from feature flags", () => {
    const tabs = filterNavTabs(
      NAV_TABS,
      { immichEnabled: false, rewindEnabled: false },
      { admin: true },
    );
    expect(tabs.map((t) => t.href)).toContain("/admin");
  });
});

describe("isAdminPath", () => {
  it("matches /admin and nested admin routes", () => {
    expect(isAdminPath("/admin")).toBe(true);
    expect(isAdminPath("/admin/")).toBe(true);
    expect(isAdminPath("/admin/jobs")).toBe(true);
  });

  it("does not match unrelated paths", () => {
    expect(isAdminPath("/")).toBe(false);
    expect(isAdminPath("/events")).toBe(false);
    expect(isAdminPath("/administrative")).toBe(false);
  });
});

describe("isSettingsPath", () => {
  it("matches /guild/{id}/settings", () => {
    expect(isSettingsPath("/guild/123/settings")).toBe(true);
    expect(isSettingsPath("/guild/abc-def/settings")).toBe(true);
    expect(isSettingsPath("/guild/123/settings/sub")).toBe(true);
  });

  it("does not match other guild routes", () => {
    expect(isSettingsPath("/guild/123")).toBe(false);
    expect(isSettingsPath("/guild")).toBe(false);
    expect(isSettingsPath("/")).toBe(false);
  });
});
