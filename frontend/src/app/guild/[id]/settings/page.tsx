import { AppShell } from "@/components/AppShell";
import { GuildSettingsForm } from "@/components/guild/GuildSettingsForm";

export default async function GuildSettingsPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  return (
    <AppShell>
      <GuildSettingsForm guildId={id} />
    </AppShell>
  );
}
