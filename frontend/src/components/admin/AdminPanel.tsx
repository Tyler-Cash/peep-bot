"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { useAdminGuilds, useCurrentUser, updateGuildFeatures } from "@/lib/hooks";

export function AdminPanel() {
  const { data: user } = useCurrentUser();
  const { data: guilds, mutate } = useAdminGuilds();
  const router = useRouter();

  useEffect(() => {
    if (user && !user.admin) {
      router.replace("/");
    }
  }, [user, router]);

  if (!user?.admin) {
    return null;
  }

  async function toggleFeature(
    guildId: string,
    feature: "immichEnabled" | "googleAutocompleteEnabled" | "rewindEnabled",
    value: boolean,
  ) {
    await updateGuildFeatures(guildId, { [feature]: value });
    await mutate();
  }

  return (
    <main className="max-w-4xl mx-auto px-4 py-8">
      <h1 className="text-2xl font-extrabold tracking-tight mb-6">Bot Admin</h1>
      <div className="overflow-x-auto rounded-card border-[1.5px] border-ink shadow-rest">
        <table className="w-full text-sm">
          <thead className="bg-paper2 border-b-[1.5px] border-ink">
            <tr>
              <th className="px-4 py-3 text-left font-extrabold">Guild</th>
              <th className="px-4 py-3 text-center font-extrabold">Immich</th>
              <th className="px-4 py-3 text-center font-extrabold">Google Places</th>
              <th className="px-4 py-3 text-center font-extrabold">Rewind</th>
            </tr>
          </thead>
          <tbody>
            {(guilds ?? []).map((g) => (
              <tr key={g.guildId} className="border-b border-ink/10 last:border-b-0 hover:bg-paper2">
                <td className="px-4 py-3 font-semibold">
                  {g.name ?? g.guildId}
                  {!g.active && (
                    <span className="ml-2 text-xs text-mute">(inactive)</span>
                  )}
                </td>
                <FeatureCell
                  value={g.immichEnabled}
                  onChange={(v) => toggleFeature(g.guildId, "immichEnabled", v)}
                />
                <FeatureCell
                  value={g.googleAutocompleteEnabled}
                  onChange={(v) => toggleFeature(g.guildId, "googleAutocompleteEnabled", v)}
                />
                <FeatureCell
                  value={g.rewindEnabled}
                  onChange={(v) => toggleFeature(g.guildId, "rewindEnabled", v)}
                />
              </tr>
            ))}
            {guilds?.length === 0 && (
              <tr>
                <td colSpan={4} className="px-4 py-6 text-center text-mute">
                  No guilds yet
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </main>
  );
}

function FeatureCell({
  value,
  onChange,
}: {
  value: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <td className="px-4 py-3 text-center">
      <input
        type="checkbox"
        checked={value}
        onChange={(e) => onChange(e.target.checked)}
        className="h-4 w-4 cursor-pointer accent-ink"
      />
    </td>
  );
}
