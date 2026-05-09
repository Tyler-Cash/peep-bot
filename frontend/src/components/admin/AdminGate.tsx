"use client";

import dynamic from "next/dynamic";
import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useCurrentUser } from "@/lib/hooks";

/**
 * The admin panel JS bundle is heavy (lifecycle pipeline, replay modal, sub-sections, charts) and
 * is only useful to a handful of service admins. Rather than ship it to everyone who navigates to
 * /admin (curious users, bots, scanners), we lazy-load it via `next/dynamic` AFTER confirming the
 * `useCurrentUser().admin` flag — `next/dynamic` only fires the import factory when the dynamic
 * component actually mounts. Non-admins that hit /admin therefore never download the panel chunk;
 * they get the redirect and a short loading flash. The same flag is set server-side by
 * BotAdminService, so this is a UX optimisation on top of an already-secured backend.
 *
 * SSR is disabled because the admin flag lives in client-side SWR state — there's no way for the
 * server render to know the user's role without re-implementing auth, so we just render nothing
 * server-side and hydrate the gate on the client.
 */
const AdminPanel = dynamic(
  () => import("./AdminPanel").then((m) => ({ default: m.AdminPanel })),
  { ssr: false, loading: () => null },
);

export function AdminGate() {
  const { data: user, isLoading } = useCurrentUser();
  const router = useRouter();

  useEffect(() => {
    if (!isLoading && user && !user.admin) {
      router.replace("/");
    }
  }, [isLoading, user, router]);

  if (isLoading || !user?.admin) return null;
  return <AdminPanel />;
}
