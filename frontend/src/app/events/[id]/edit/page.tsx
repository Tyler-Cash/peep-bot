import { AppShell } from "@/components/AppShell";
import { EditEventForm } from "@/components/event/EditEventForm";

export default async function Page({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  return (
    <AppShell>
      <EditEventForm id={id} />
    </AppShell>
  );
}
